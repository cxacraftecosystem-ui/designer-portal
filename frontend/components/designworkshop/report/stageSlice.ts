type HeadingLike = { type: string; level?: number; runs?: Array<{ text?: string | null }> };

function runsText(runs: HeadingLike["runs"]): string {
  return (runs ?? []).map((run) => run.text ?? "").join("");
}

export function foldTitle(value: string): string {
  return value
    .replace(/^[\s\d.]+/, "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

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

export function sliceStageBlocks<B extends HeadingLike>(blocks: B[], stageTitle: string): B[] | null {
  const range = stageBlockRange(blocks, stageTitle);
  return range ? blocks.slice(range[0], range[1]) : null;
}

export function spliceStageBlocks<B extends HeadingLike>(base: B[], live: B[], stageTitle: string): B[] | null {
  const into = stageBlockRange(base, stageTitle);
  const from = stageBlockRange(live, stageTitle);
  if (!into || !from) return null;
  return [...base.slice(0, into[0]), ...live.slice(from[0], from[1]), ...base.slice(into[1])];
}

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
