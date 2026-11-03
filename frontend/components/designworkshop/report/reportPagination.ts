export type FlowItem = {
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

export type PageSlice = {
  blockIndex: number;
  part: number;
  parts: number;
  offsetPx: number;
  heightPx: number;
  gapBeforePx: number;
  repeatHeaderPx: number;
  scale: number;
};

export type PackedPage = {
  pageNumber: number;
  isCover: boolean;
  slices: PageSlice[];
  usedPx: number;
  capacityPx: number;
};

export type ScaledBlock = { blockIndex: number; pageNumber: number; scale: number };

export type PackedDocument = {
  pages: PackedPage[];
  scaled: ScaledBlock[];
  divided: number[];
  abandoned: number[];
};

export type PageBudget = {
  contentPx: number;
  coverContentPx?: number;
};

const EPSILON = 0.5;

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
    const firstPiece = next.splittable && offsets.length > 0 ? offsets[0] : next.heightPx;
    need += (next.gapBeforePx ?? 0) + firstPiece;
  }
  return need > contentPx ? 0 : need;
}

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

    if (item.isCover || item.breakBefore) closePage();
    if (item.isCover) cover = true;

    if (item.keepWithNext && slices.length > 0) {
      const need = keepRunHeight(items, i, contentPx);
      if ((item.gapBeforePx ?? 0) + need > capacity() - used + EPSILON) closePage();
    }

    const offsets = usableOffsets(item);
    const canSplit = item.splittable && offsets.length > 0;
    const made: PageSlice[] = [];
    let cursor = 0;
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

      if (!canSplit || (item.preferWhole && first && item.heightPx <= cap + EPSILON)) {
        if (slices.length > 0) {
          closePage();
          continue;
        }
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

  if (!pages.length) {
    pages.push({ pageNumber: 1, isCover: false, slices: [], usedPx: 0, capacityPx: contentPx });
  }

  return { pages, scaled, divided, abandoned };
}
