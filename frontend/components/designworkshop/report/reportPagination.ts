/**
 * Blocks flowing into fixed-height pages — the arithmetic, with no DOM and no React in it.
 *
 * WHAT THIS REPLACED, AND WHY THE OLD ANSWER WAS NOT A SMALL ONE. `splitIntoSheets` cut the block
 * stream only where the template DECLARED a break — a `PAGEBREAK` block or a `COVER` — and the
 * sheet it produced was `min-height: var(--rp-page-h)`. A minimum is not a page. One long stage
 * therefore rendered as a single "sheet" several times taller than the paper it was drawing, and
 * the screen whose entire job is to answer "does this fit on the page?" answered it by growing the
 * page. Everything below exists to end that: a page is a fixed box, content flows into it, and
 * when the box is full the next block starts a new one — which is what paper does.
 *
 * PURE, AND DELIBERATELY SO. There is no React renderer in this repository's devDependencies, so a
 * judgement made inside JSX is only ever exercised by somebody looking at a screen; the split here
 * is the one `components/ui/selectFilter.ts` and `components/data/cappedList.ts` already make, for
 * that reason. `frontend/e2e/report-pagination-unit.spec.ts` calls {@link packPages} directly.
 *
 * ── WHAT DECIDES WHERE A BREAK MAY FALL: THE SERVER, NOT THIS FILE ──────────────────────────
 *
 * There are FOUR renderers of this document — `backend/app/services/report_docx.py`,
 * `report_pdf.py`, and the two on-device Kotlin writers — and a ministry receives what they
 * produce, so they must agree line for line. This module is not a fifth: it lays out the blocks
 * `GET /report/preview` already built and never decides what one SAYS. Every rule below is read
 * off `report_pdf.py`, which is the writer whose output is a fixed page, and each is cited where
 * it is applied:
 *
 *   · a PARAGRAPH breaks between LINES            — `_draw_lines` calls `_ensure(line.height)`
 *   · a BULLETLIST breaks between ITEMS           — `_block_bullets`
 *   · a TABLE breaks between ROWS **and repeats
 *     its header over the continuation**          — `place_row` calls `place_header()` after
 *                                                   `_new_page()`, "exactly as Word repeats a
 *                                                   `<w:tblHeader/>` row"
 *   · a KEYVALUE grid breaks between PAIRS        — `_simple_grid`
 *   · an IMAGEGRID breaks between GRID ROWS       — `_block_image_grid` loops `range(0, n, cols)`
 *                                                   and `_ensure`s one row at a time
 *   · a CALLOUT is moved WHOLE while it fits any
 *     page, and only cut when it fits none        — one `_cut_row` over the whole flow, whose
 *                                                   docstring calls the fits-a-page case "an
 *                                                   ordinary break"
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
 * ── THE ONE PLACE THE PREVIEW CANNOT DO WHAT THE FILE DOES, STATED RATHER THAN HIDDEN ────────
 *
 * A single unsplittable thing taller than a whole page. The .pdf never has one for a picture,
 * because it caps a photograph at 0.62 of the text column (`_block_image`), a chart or map at 0.58
 * (`_block_figure`) and a grid photograph at 0.30 (`_block_image_grid`) — it SCALES, so the
 * picture always fits. For the rest — a table row of six thousand characters, a callout longer
 * than a page — the .pdf CUTS, mid-cell, and repeats the header above the remainder; the preview
 * cannot cut mid-cell without measuring every line box inside every cell, so it does what the
 * writers do to a picture instead: it scales the block down onto one page and {@link PackedDocument}
 * reports it in `scaled` so the screen can say so. Nothing is clipped and nothing is dropped
 * silently — a silently clipped figure is the bug class this repository hates most, and
 * `_cut_row`'s own docstring settles the principle for the file: "A REFUSAL, NOT A TRUNCATION."
 */

/**
 * One block, measured, with the rules that govern where it may be cut.
 *
 * The five fields the packer genuinely needs are `blockIndex`, `heightPx`, `breakBefore`,
 * `keepWithNext` and `splittable`. Everything else is optional and every one of them earns its
 * place by naming a behaviour of a FILE writer that would otherwise be lost:
 *
 *   `gapBeforePx`     the space between this block and the one above it, measured rather than
 *                     recomputed from the stylesheet. Spent only mid-page: a page break eats the
 *                     gap, the same way `_new_page` resets the cursor to the top margin.
 *   `breakOffsetsPx`  the offsets, from this block's own top, at which a cut is LEGAL — row
 *                     bottoms, item bottoms, line-box bottoms. A `splittable` block with none of
 *                     them behaves as unsplittable, which is the honest reading of "the measurer
 *                     could not find a place to cut this".
 *   `repeatHeaderPx`  the height of the header a continuation page redraws (tables only).
 *   `preferWhole`     move to the next page whole rather than divide, while the block fits a page
 *                     at all. `_cut_row` refuses to cut in exactly that case.
 *   `breakAfter`      the block ends its page — a TOC, which `_block_toc` follows with `_new_page`.
 *   `isCover`         one page to itself, and no running furniture on it.
 */
export type FlowItem = {
  /** Index into the caller's own block array. Slices carry it back so the caller can draw them. */
  blockIndex: number;
  heightPx: number;
  gapBeforePx?: number;
  breakBefore: boolean;
  breakAfter?: boolean;
  keepWithNext: boolean;
  splittable: boolean;
  breakOffsetsPx?: number[];
  repeatHeaderPx?: number;
  preferWhole?: boolean;
  isCover?: boolean;
};

/**
 * One block, or one piece of one, on one page.
 *
 * `offsetPx` and `heightPx` are a WINDOW onto the block's own box: the caller draws the whole
 * block inside a clip of `heightPx`, pushed up by `offsetPx`. That is what makes a continuation
 * exact rather than re-laid-out — the second half of a paragraph is the same line boxes the first
 * half was measured against, not a fresh wrap at a different width.
 */
export type PageSlice = {
  blockIndex: number;
  /** 0-based piece of this block; 0 for a block that was not divided. */
  part: number;
  /** How many pieces the block ended up in. 1 means whole. */
  parts: number;
  /** Where this piece starts inside the block, in px from the block's top. */
  offsetPx: number;
  /** How tall this piece is, before `scale`. */
  heightPx: number;
  /** The measured gap above it. Always 0 for the first slice on a page. */
  gapBeforePx: number;
  /** A table header redrawn above a continuation, as `place_row` does. 0 on the first piece. */
  repeatHeaderPx: number;
  /** 1 normally. Below 1 for a block too tall for any page — reported in {@link PackedDocument}. */
  scale: number;
};

export type PackedPage = {
  /** 1-based: the N in the "Page N of M" every renderer draws in its own foot. */
  pageNumber: number;
  /** The cover is one page and carries no running head or foot, in the file and here. */
  isCover: boolean;
  slices: PageSlice[];
  /** What the slices came to, including gaps, repeated headers and scaling. */
  usedPx: number;
  capacityPx: number;
};

export type ScaledBlock = { blockIndex: number; pageNumber: number; scale: number };

export type PackedDocument = {
  pages: PackedPage[];
  /**
   * Blocks that could not fit a page and were drawn scaled down onto one. NEVER empty silently:
   * the caller prints the count, because a figure quietly shrunk is a figure a designer approves
   * at a size the file will not use.
   */
  scaled: ScaledBlock[];
  /** Blocks divided across pages, as the file divides them. */
  divided: number[];
  /**
   * Blocks the packer gave up on — always empty in practice, and reported rather than swallowed.
   * The loop below advances the cursor at least every second iteration, so the bound cannot be
   * reached by any measurement a browser can produce; if it ever is, a block is MISSING from the
   * pages and the reader has to be told rather than shown a document with a hole in it.
   */
  abandoned: number[];
};

export type PageBudget = {
  /** The content box of an ordinary page: the sheet less its margins, its running head and foot. */
  contentPx: number;
  /** The cover's, which is taller because it carries no furniture. Defaults to `contentPx`. */
  coverContentPx?: number;
};

/**
 * Half a CSS pixel.
 *
 * Heights here come from `getBoundingClientRect`, which is sub-pixel: a block measured at
 * 247.0000001 px against a content box of 247 px is the same block, and treating it as an overflow
 * would start a new page for a rounding error and do it on every page of the document.
 */
const EPSILON = 0.5;

/** Ascending, inside the block, and de-duplicated — a repeated offset would cut a zero-height slice. */
function usableOffsets(item: FlowItem): number[] {
  const raw = item.breakOffsetsPx ?? [];
  const seen: number[] = [];
  for (const offset of raw) {
    if (!Number.isFinite(offset)) continue;
    if (offset <= EPSILON || offset >= item.heightPx - EPSILON) continue;
    if (seen.some((kept) => Math.abs(kept - offset) <= EPSILON)) continue;
    seen.push(offset);
  }
  return seen.sort((a, b) => a - b);
}

/**
 * What a heading needs beneath it before it may be drawn.
 *
 * `_block_heading` reserves `lead + its own lines + trail + self.base_size * 1.32` — the last term
 * being ONE BODY LINE, and that term is the whole of keep-with-next: "a heading with nothing under
 * it is still an orphan even if it fits". So the run of consecutive keep-with-next blocks is
 * measured together with the first legal piece of whatever follows them.
 *
 * AN IMPOSSIBLE KEEP IMPOSES NO CONSTRAINT AT ALL, which is the half that is easy to get wrong.
 * When the run plus the first piece of what follows would not fit an EMPTY page, turning the page
 * cannot satisfy it — so this returns 0 rather than a demand nothing can meet, and the heading is
 * placed where it stands. Demanding a whole page instead would send every heading followed by a
 * full-page figure to a fresh sheet and leave the bottom of the previous one blank for nothing. The
 * .pdf paid for that same reading once in `_block_table`, where reserving a header plus an
 * over-tall first row "turned a page the row was going to start on anyway: the table's own first
 * page came out completely blank".
 */
function keepRunHeight(items: FlowItem[], index: number, contentPx: number): number {
  let need = 0;
  let k = index;
  while (k < items.length && items[k].keepWithNext) {
    need += (k > index ? items[k].gapBeforePx ?? 0 : 0) + items[k].heightPx;
    k += 1;
    if (need > contentPx) return 0;
  }
  const next = k < items.length ? items[k] : null;
  if (next && !next.breakBefore && !next.isCover) {
    const offsets = usableOffsets(next);
    // The first piece of what follows. For a divisible block that is its first legal cut — one line
    // of a paragraph, one row of a table, which is exactly what `_block_heading` reserves. For an
    // indivisible one it is the whole block: reserving a fraction of a figure that would then move
    // to the next page on its own leaves the heading orphaned anyway, which is the outcome
    // keep-with-next exists to prevent.
    const firstPiece = next.splittable && offsets.length > 0 ? offsets[0] : next.heightPx;
    need += (next.gapBeforePx ?? 0) + firstPiece;
  }
  return need > contentPx ? 0 : need;
}

/**
 * Flow the measured blocks into fixed-height pages.
 *
 * Deterministic and total: the same input always produces the same pages, and every block reaches
 * one of them — whole, divided, or scaled and reported.
 */
export function packPages(items: FlowItem[], budget: PageBudget): PackedDocument {
  const contentPx = Math.max(1, budget.contentPx);
  const coverPx = Math.max(1, budget.coverContentPx ?? contentPx);

  const pages: PackedPage[] = [];
  const scaled: ScaledBlock[] = [];
  const divided: number[] = [];
  const abandoned: number[] = [];

  let slices: PageSlice[] = [];
  let used = 0;
  let cover = false;

  const capacity = () => (cover ? coverPx : contentPx);

  const closePage = () => {
    if (!slices.length) {
      cover = false;
      return;
    }
    pages.push({
      pageNumber: pages.length + 1,
      isCover: cover,
      slices,
      usedPx: used,
      capacityPx: capacity()
    });
    slices = [];
    used = 0;
    cover = false;
  };

  for (let i = 0; i < items.length; i += 1) {
    const item = items[i];

    // A break the template asked for is a break in the FILE — both writers honour a `PAGEBREAK`
    // exactly — so it wins over everything the measurements say, including a page with room left.
    if (item.isCover || item.breakBefore) closePage();
    if (item.isCover) cover = true;

    // Keep-with-next, decided BEFORE the block is placed: if the heading and what has to follow it
    // do not both fit here, the heading turns the page rather than being drawn and orphaned.
    if (item.keepWithNext && slices.length > 0) {
      const need = keepRunHeight(items, i, contentPx);
      if ((item.gapBeforePx ?? 0) + need > capacity() - used + EPSILON) closePage();
    }

    const offsets = usableOffsets(item);
    const canSplit = item.splittable && offsets.length > 0;
    const made: PageSlice[] = [];
    let cursor = 0;
    // Every iteration either advances `cursor` past one offset or turns a page, and a page can
    // only be turned when one is open — so two iterations per offset plus a handful is a hard
    // bound rather than a hopeful one. It exists so that a measurement this file has not imagined
    // cannot hang the tab; `abandoned` is what says out loud that it was reached.
    const limit = 2 * (offsets.length + 2) + 4;
    let steps = 0;

    while (cursor < item.heightPx - EPSILON || made.length === 0) {
      if (steps >= limit) {
        abandoned.push(item.blockIndex);
        break;
      }
      steps += 1;

      const first = made.length === 0;
      const gap = first && slices.length > 0 ? item.gapBeforePx ?? 0 : 0;
      const header = first ? 0 : item.repeatHeaderPx ?? 0;
      const cap = capacity();
      const free = cap - used - gap - header;
      const left = item.heightPx - cursor;

      const emit = (height: number, scale: number) => {
        const slice: PageSlice = {
          blockIndex: item.blockIndex,
          part: made.length,
          parts: 1,
          offsetPx: cursor,
          heightPx: height,
          gapBeforePx: gap,
          repeatHeaderPx: header,
          scale
        };
        slices.push(slice);
        made.push(slice);
        used += gap + header + height * scale;
      };

      if (left <= free + EPSILON) {
        emit(left, 1);
        cursor = item.heightPx;
        break;
      }

      // The whole-block strategies: a block that may not be divided at all, and a block that may
      // be but would rather not be while it still fits a page of its own (`_cut_row`'s "ordinary
      // break"). Both answers are the same — turn the page, and try again on an empty one.
      if (!canSplit || (item.preferWhole && first && item.heightPx <= cap + EPSILON)) {
        if (slices.length > 0) {
          closePage();
          continue;
        }
        // An empty page and it still does not fit: taller than any page there is. Scaled onto one
        // page and reported, never clipped. See this file's header for why scaling is the choice.
        const scale = cap / left;
        emit(left, scale);
        scaled.push({ blockIndex: item.blockIndex, pageNumber: pages.length + 1, scale });
        cursor = item.heightPx;
        break;
      }

      const reach = cursor + free;
      let cut = -1;
      for (const offset of offsets) {
        if (offset > cursor + EPSILON && offset <= reach + EPSILON) cut = offset;
      }

      if (cut < 0) {
        if (slices.length > 0) {
          closePage();
          continue;
        }
        // A single row taller than an empty page. The .pdf cuts it mid-cell and repeats the
        // header; the preview scales that one piece instead and says so, for the reason in the
        // header. The cursor still advances, so the rest of the block paginates normally.
        const next = offsets.find((offset) => offset > cursor + EPSILON) ?? item.heightPx;
        const piece = next - cursor;
        const scale = free / piece;
        emit(piece, scale);
        scaled.push({ blockIndex: item.blockIndex, pageNumber: pages.length + 1, scale });
        cursor = next;
        if (cursor >= item.heightPx - EPSILON) break;
        closePage();
        continue;
      }

      emit(cut - cursor, 1);
      cursor = cut;
      closePage();
    }

    for (let k = 0; k < made.length; k += 1) {
      made[k].part = k;
      made[k].parts = made.length;
    }
    if (made.length > 1) divided.push(item.blockIndex);

    if (item.isCover || item.breakAfter) closePage();
  }

  closePage();

  // A document whose every block was a page break still has to render as something. One empty
  // sheet reads as "this template produced nothing", which is the truth and is what the designer
  // needs to see; zero sheets would render as an empty panel indistinguishable from a load that
  // had not finished. (Carried forward verbatim from `splitIntoSheets`, which this replaced.)
  if (!pages.length) {
    pages.push({ pageNumber: 1, isCover: false, slices: [], usedPx: 0, capacityPx: contentPx });
  }

  return { pages, scaled, divided, abandoned };
}
