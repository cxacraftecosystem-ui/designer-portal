/**
 * The pure half of the live document preview: where one stage sits inside the whole document, how a
 * freshly built copy of that stage is put back into it, and how a rebuilt document keeps the blocks
 * that did not change.
 *
 * NO REACT AND NO DOM, so `e2e/stage-slice-unit.spec.ts` drives it in Node and
 * `android/.../DwStageSlice.kt` is a port of it under the SAME FUNCTION NAMES. Both are held to one
 * fixture, `shared/report-parity/stage-slice-vectors.json`, so the laptop and the handset cannot
 * disagree about which pages belong to the stage a designer is typing into.
 *
 * WHY A SLICE AND A SPLICE, AND NOT A SECOND RENDERER. The blocks are always the report builder's
 * own — the server's (`GET /report/preview`) or its parity-tested port in `lib/offlineReport`, which
 * `offline-report-parity-unit.spec.ts` holds to the server block for block. Nothing here derives a
 * block from stage data. The live preview builds this ONE stage from what is on the form right now,
 * with that same builder, and puts its blocks where the stage's blocks already stand in the whole
 * document, so the pages around it keep their real content and their real page numbers.
 */

type HeadingLike = { type: string; level?: number; runs?: Array<{ text?: string | null }> };

/** A heading's text, flattened from its runs. */
function runsText(runs: HeadingLike["runs"]): string {
  return (runs ?? []).map((run) => run.text ?? "").join("");
}

/**
 * Fold for comparing a heading against a stage title: case, surrounding space, runs of whitespace
 * and a leading section NUMBER come off. Some templates print "4." inside the runs and others in
 * `HeadingBlock.number`, and a comparison that kept it would match on one template and not the next.
 */
export function foldTitle(value: string): string {
  return value
    .replace(/^[\s\d.]+/, "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

/**
 * Where one stage's blocks run in a document: `[start, end)`, or `null` when its heading is not in
 * it. From the heading whose text is the stage's title to the next heading at the same level or
 * SHALLOWER — shallower because the last stage under a part heading is followed by the next PART.
 *
 * `null` and not an empty range: "this stage prints nothing" and "this stage's heading could not be
 * found" are different answers, and the caller says which one it is looking at.
 */
export function stageBlockRange<B extends HeadingLike>(blocks: B[], stageTitle: string): [number, number] | null {
  const wanted = foldTitle(stageTitle);
  if (!wanted) return null;
  const startAt = blocks.findIndex((block) => block.type === "HEADING" && foldTitle(runsText(block.runs)) === wanted);
  if (startAt < 0) return null;
  const level = blocks[startAt].level ?? 1;
  let endAt = blocks.length;
  for (let at = startAt + 1; at < blocks.length; at += 1) {
    const block = blocks[at];
    if (block.type === "HEADING" && (block.level ?? 1) <= level) {
      endAt = at;
      break;
    }
  }
  return [startAt, endAt];
}

/** The blocks belonging to one stage, or `null` when its heading is not in the document. */
export function sliceStageBlocks<B extends HeadingLike>(blocks: B[], stageTitle: string): B[] | null {
  const range = stageBlockRange(blocks, stageTitle);
  return range ? blocks.slice(range[0], range[1]) : null;
}

/**
 * The whole document `base`, with this stage's blocks replaced by the stage's blocks from `live`.
 *
 * Returns `null` when the splice cannot be made honestly — the stage's heading is missing from
 * either document — and the caller then shows the live document whole rather than guessing where
 * the stage would go. That is the case of a stage that printed nothing at the last save (so the
 * base has no heading for it) and now does.
 */
export function spliceStageBlocks<B extends HeadingLike>(base: B[], live: B[], stageTitle: string): B[] | null {
  const into = stageBlockRange(base, stageTitle);
  const from = stageBlockRange(live, stageTitle);
  if (!into || !from) return null;
  return [...base.slice(0, into[0]), ...live.slice(from[0], from[1]), ...base.slice(into[1])];
}

/**
 * `next`, with every block that is structurally identical to the block at the same place in
 * `previous` replaced by the previous OBJECT.
 *
 * A rebuild produces new objects for every block, and identity is what the renderer, the
 * measurement cache and React's memo key off. Sharing the unchanged ones means a keystroke in one
 * paragraph re-renders and re-measures that paragraph and nothing else, which is what keeps typing
 * smooth on a forty-page document with forty photographs in it.
 *
 * Compared by position first and then by a content index, so inserting a paragraph near the top
 * does not mark everything below it as changed.
 */
export function stableBlocks<B>(previous: B[] | null | undefined, next: B[]): B[] {
  if (!previous || !previous.length) return next;
  const byKey = new Map<string, B>();
  const keys = previous.map((block) => JSON.stringify(block));
  previous.forEach((block, index) => {
    if (!byKey.has(keys[index])) byKey.set(keys[index], block);
  });
  let changed = previous.length !== next.length;
  const out = next.map((block, index) => {
    const key = JSON.stringify(block);
    if (index < previous.length && keys[index] === key) return previous[index];
    changed = true;
    return byKey.get(key) ?? block;
  });
  return changed ? out : previous;
}
