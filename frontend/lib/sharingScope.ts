import type { DataAccessGrant, DataAccessStatus, DataAccessTier } from "@/lib/types";

export const TIER_RANK: Record<DataAccessTier, number> = { DOWNLOAD: 1, COMMENT: 2, EDIT: 3 };

export type Standing = { status: DataAccessStatus; tier: DataAccessTier; allData: boolean; keys: Set<string> };

export type Scope = { allData: boolean; keys: Set<string> };

export type Change = "new" | "same" | "raise" | "reduce";

export function scopeKey(recordType: string, recordId: string): string {
  return `${recordType}::${recordId}`;
}

export function splitScopeKey(key: string): { recordType: string; recordId: string } {
  const separator = key.indexOf("::");
  return separator < 0
    ? { recordType: key, recordId: "" }
    : { recordType: key.slice(0, separator), recordId: key.slice(separator + 2) };
}

export function scopeKeysOf(grant: DataAccessGrant): Set<string> {
  return new Set((grant.scopeItems ?? []).map((item) => scopeKey(item.recordType, item.recordId)));
}

export function standingsBy(rows: DataAccessGrant[], personField: "granteeId" | "ownerId"): Map<string, Standing> {
  const map = new Map<string, Standing>();
  rows.forEach((row) =>
    map.set(row[personField], { status: row.status, tier: row.tier, allData: row.allData, keys: scopeKeysOf(row) })
  );
  return map;
}

export function covers(wider: Scope, narrower: Scope): boolean {
  if (wider.allData) return true;
  if (narrower.allData) return false;
  for (const key of narrower.keys) if (!wider.keys.has(key)) return false;
  return true;
}

export function classifyChange(standing: Standing | undefined, next: Scope & { tier: DataAccessTier }): Change {
  if (!standing || standing.status !== "GRANTED") return "new";
  const held: Scope = { allData: standing.allData, keys: standing.keys };
  if (TIER_RANK[next.tier] < TIER_RANK[standing.tier] || !covers(next, held)) return "reduce";
  if (TIER_RANK[next.tier] === TIER_RANK[standing.tier] && covers(held, next)) return "same";
  return "raise";
}

export type ScopeRemoval = { kind: "none" } | { kind: "allData" } | { kind: "records"; keys: string[] };

export function scopeRemoval(standing: Standing, next: Scope): ScopeRemoval {
  if (next.allData) return { kind: "none" };
  if (standing.allData) return { kind: "allData" };
  const keys = [...standing.keys].filter((key) => !next.keys.has(key));
  return keys.length ? { kind: "records", keys } : { kind: "none" };
}

export function scopeWords(standing: Standing): string {
  return standing.allData ? "all data" : plural(standing.keys.size, "record");
}

export function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

export const NAMES_IN_PROSE = 6;

export function nameList(names: string[], max = NAMES_IN_PROSE): string {
  if (names.length <= max) return names.join(", ");
  return `${names.slice(0, max).join(", ")} and ${plural(names.length - max, "other")}`;
}
