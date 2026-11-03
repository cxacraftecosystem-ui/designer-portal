import type { DwEntryData, DwRegistry, DwRow, DwValue } from "@/lib/designWorkshops";

export type RatingRound = "PEER" | "POOL";

export const RATEABLE_ENTITY_KEYS = ["sketch", "prototype"] as const;
export type RateableEntityKey = (typeof RATEABLE_ENTITY_KEYS)[number];

export function isRateableEntityKey(value: string): value is RateableEntityKey {
  return (RATEABLE_ENTITY_KEYS as readonly string[]).includes(value);
}

export type DesignRating = {
  id: string;
  subjectId: string | null;
  round: string;
  score: number | null;
  comment: string | null;
  suggestion: string | null;
  ratedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  mine: boolean;
  reviewerId?: string;
};

export type RankedItem = {
  subjectId: string;
  entityKey: string;
  label: string;
  workshopId: string;
  score: number | null;
  ratingCount: number;
  defaultPosition: number;
  placedPosition: number;
  myRating: DesignRating | null;
  ordinal?: number;
};

export type RoundRanking = {
  workshopId: string;
  entityKey: string;
  round: string;
  items: RankedItem[];
};

export type SubjectLedger = {
  subject: { id: string; entityKey: string; label: string; workshopId: string; ordinal?: number };
  round: string;
  summary: { score: number | null; ratingCount: number };
  ratings: DesignRating[];
  canReadLedger: boolean;
  namesShown: boolean;
};

export function mayArrange(items: readonly RankedItem[]): boolean {
  return items.length > 0 && items.every((item) => typeof item.ordinal === "number");
}

export function scoreOrder(items: readonly RankedItem[]): string[] {
  return [...items]
    .sort((a, b) => a.defaultPosition - b.defaultPosition || a.subjectId.localeCompare(b.subjectId))
    .map((item) => item.subjectId);
}

export function placedOrder(items: readonly RankedItem[]): string[] {
  return [...items]
    .sort((a, b) => a.placedPosition - b.placedPosition || a.subjectId.localeCompare(b.subjectId))
    .map((item) => item.subjectId);
}

export function heldOrder(rows: readonly DwRow[]): string[] {
  const ids: string[] = [];
  for (const row of rows) {
    const id = row._entryId;
    if (typeof id === "string" && id) ids.push(id);
  }
  return ids;
}

export function openingOrder(
  items: readonly RankedItem[],
  fixed: FixedOrderStamp | null,
  held: readonly string[] | null = null
): string[] {
  if (!fixed) return scoreOrder(items);
  return held && held.length > 0 ? reconcileOrder(held, items) : placedOrder(items);
}

export function reconcileOrder(order: readonly string[], items: readonly RankedItem[]): string[] {
  const present = new Set(items.map((item) => item.subjectId));
  const kept = order.filter((id) => present.has(id));
  const seen = new Set(kept);
  return [...kept, ...scoreOrder(items).filter((id) => !seen.has(id))];
}

export function sameOrder(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((id, index) => id === b[index]);
}

export function moveBy(order: readonly string[], id: string, delta: number): string[] {
  const from = order.indexOf(id);
  if (from < 0) return [...order];
  return moveTo(order, from, from + delta);
}

export function moveTo(order: readonly string[], from: number, to: number): string[] {
  const next = [...order];
  if (from < 0 || from >= next.length) return next;
  const target = Math.max(0, Math.min(next.length - 1, to));
  if (target === from) return next;
  const [moved] = next.splice(from, 1);
  next.splice(target, 0, moved);
  return next;
}

export type FixedOrderStamp = { by: string; at: string };

export const RANK_FIXED_BY_FIELD = "rankFixedBy";
export const RANK_FIXED_AT_FIELD = "rankFixedAt";

function text(value: DwValue | undefined): string {
  return typeof value === "string" ? value.trim() : "";
}

export function fixedOrderStamp(rows: readonly DwRow[]): FixedOrderStamp | null {
  let best: FixedOrderStamp | null = null;
  for (const row of rows) {
    const by = text(row[RANK_FIXED_BY_FIELD]);
    const at = text(row[RANK_FIXED_AT_FIELD]);
    if (!by || !at) continue;
    if (!best || at > best.at) best = { by, at };
  }
  return best;
}

export function todayStamp(now: Date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export function arrangeRows(
  rows: readonly DwRow[],
  order: readonly string[],
  stamp: FixedOrderStamp | null
): DwRow[] {
  const byId = new Map<string, DwRow>();
  for (const row of rows) {
    const id = row._entryId;
    if (typeof id === "string" && id) byId.set(id, row);
  }
  const named: DwRow[] = [];
  for (const id of order) {
    const row = byId.get(id);
    if (row) {
      named.push(row);
      byId.delete(id);
    }
  }
  const rest = rows.filter((row) => typeof row._entryId !== "string" || byId.has(row._entryId));
  return [...named, ...rest].map((row) => ({
    ...row,
    [RANK_FIXED_BY_FIELD]: stamp ? stamp.by : "",
    [RANK_FIXED_AT_FIELD]: stamp ? stamp.at : ""
  }));
}

export function stageKeyForEntity(registry: DwRegistry, entityKey: string): string | null {
  for (const stage of registry.stages) {
    for (const entity of stage.entities) {
      if (entity.key === entityKey && entity.cardinality === "COLLECTION") return stage.key;
    }
  }
  return null;
}

export function rowSubtitle(row: DwEntryData | undefined): string {
  if (!row) return "";
  const parts: string[] = [];
  for (const key of ["sketchNo", "prototypeCode", "designerName", "makerName"]) {
    const value = text(row[key]);
    if (value) parts.push(value);
  }
  return parts.join(" · ");
}
