/**
 * WHAT A SAVE ON THE SHARING PAGE WOULD DO TO ACCESS SOMEBODY ALREADY HOLDS.
 *
 * `POST /data-access/grants` goes through `_upsert_grant`, which writes the single (owner, grantee)
 * row IN PLACE and reconciles its scope items to exactly what the payload names. A grant is
 * therefore a REPLACEMENT and never an addition: including a colleague who holds EDIT on everything
 * in a "DOWNLOAD on three records" action does not add three records to their access, it takes the
 * rest of it away. That is the one outcome on that screen which destroys something, and it is
 * invisible from a picker that shows only names.
 *
 * This file is the whole of that judgement, kept out of the page for the reason
 * `components/ui/selectFilter.ts` and `components/data/cappedList.ts` are: there is no React
 * renderer in devDependencies, so a decision made inside JSX is only ever exercised by somebody
 * looking at a screen. Everything here is pure and is pinned by `e2e/sharing-scope-unit.spec.ts`.
 *
 * IT IS THE ONLY PLACE THAT SPELLS A SCOPE KEY. `${recordType}::${recordId}` appears here and
 * nowhere else, because the two sides of that string are compared against a set built from the
 * server's own rows — a second spelling would compare a scope against itself and answer "nothing is
 * being removed" every time.
 */

import type { DataAccessGrant, DataAccessStatus, DataAccessTier } from "@/lib/types";

/**
 * The tier ladder as ranks, mirroring `TIER_ORDER` in `backend/app/services/access.py`.
 *
 * The tiers are cumulative rather than a set of independent permissions: the backend compares them
 * with `tier_at_least`, a `>=` against these ranks, and its own catalogue text says so outright —
 * COMMENT is "everything in Download, plus…", EDIT is "everything in Comment, plus…". A person holds
 * exactly one rung.
 */
export const TIER_RANK: Record<DataAccessTier, number> = { DOWNLOAD: 1, COMMENT: 2, EDIT: 3 };

/** One person's existing relationship, flattened out of their single (owner, grantee) grant row. */
export type Standing = { status: DataAccessStatus; tier: DataAccessTier; allData: boolean; keys: Set<string> };

/** A reach over records: everything, or an explicit set of `type::id` keys. */
export type Scope = { allData: boolean; keys: Set<string> };

export type Change = "new" | "same" | "raise" | "reduce";

/** The one spelling of a scope item as a set member. See the file header. */
export function scopeKey(recordType: string, recordId: string): string {
  return `${recordType}::${recordId}`;
}

/** The two halves back out of a key, for building the payload the API expects. */
export function splitScopeKey(key: string): { recordType: string; recordId: string } {
  const separator = key.indexOf("::");
  return separator < 0
    ? { recordType: key, recordId: "" }
    : { recordType: key.slice(0, separator), recordId: key.slice(separator + 2) };
}

export function scopeKeysOf(grant: DataAccessGrant): Set<string> {
  return new Set((grant.scopeItems ?? []).map((item) => scopeKey(item.recordType, item.recordId)));
}

/**
 * People indexed by id. Safe as a plain overwrite: the grant table is uniquely keyed on
 * (ownerId, granteeId), so a person can appear at most once on either side.
 */
export function standingsBy(rows: DataAccessGrant[], personField: "granteeId" | "ownerId"): Map<string, Standing> {
  const map = new Map<string, Standing>();
  rows.forEach((row) =>
    map.set(row[personField], { status: row.status, tier: row.tier, allData: row.allData, keys: scopeKeysOf(row) })
  );
  return map;
}

/** Does `wider` reach every record `narrower` reaches? */
export function covers(wider: Scope, narrower: Scope): boolean {
  if (wider.allData) return true;
  if (narrower.allData) return false;
  for (const key of narrower.keys) if (!wider.keys.has(key)) return false;
  return true;
}

/** What pressing Grant would do to one person who already holds something. */
export function classifyChange(standing: Standing | undefined, next: Scope & { tier: DataAccessTier }): Change {
  if (!standing || standing.status !== "GRANTED") return "new";
  const held: Scope = { allData: standing.allData, keys: standing.keys };
  if (TIER_RANK[next.tier] < TIER_RANK[standing.tier] || !covers(next, held)) return "reduce";
  if (TIER_RANK[next.tier] === TIER_RANK[standing.tier] && covers(held, next)) return "same";
  return "raise";
}

/**
 * WHICH RECORDS A SAVE WOULD TAKE OUT OF SOMEBODY'S GRANT — the half the warning was missing.
 *
 * The page could already say THAT access would be lowered; it could not say WHAT would be lost, and
 * "this lowers access Priya already holds" is not something an owner can check before pressing a
 * button. Three answers, and the middle one is not a failure to compute:
 *
 *  * `none`     — nothing leaves the scope. Either the new scope is all-data (which reaches every
 *                 record there is), or it is a superset of what they hold. A tier drop is still a
 *                 reduction; it is just not a reduction of WHICH records.
 *  * `allData`  — they hold ALL of this owner's data and the new scope is a subset, so everything
 *                 except the ticked records goes. There is no list to give: "all data" is not an
 *                 enumeration on the server either (`DataAccessGrant.allData` names no rows), and
 *                 inventing one from whatever the picker happens to have fetched would be a list
 *                 that is wrong by exactly the records the picker could not show.
 *  * `records`  — the exact keys leaving, which the caller resolves to names as far as it can and
 *                 COUNTS the rest rather than dropping them.
 */
export type ScopeRemoval = { kind: "none" } | { kind: "allData" } | { kind: "records"; keys: string[] };

export function scopeRemoval(standing: Standing, next: Scope): ScopeRemoval {
  if (next.allData) return { kind: "none" };
  if (standing.allData) return { kind: "allData" };
  const keys = [...standing.keys].filter((key) => !next.keys.has(key));
  return keys.length ? { kind: "records", keys } : { kind: "none" };
}

/** How a person's existing reach reads in a sentence. */
export function scopeWords(standing: Standing): string {
  return standing.allData ? "all data" : plural(standing.keys.size, "record");
}

export function plural(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

/** Names read out in full in a dialog before the tail collapses to a count. */
export const NAMES_IN_PROSE = 6;

/**
 * A list of names with its tail collapsed — and the collapse STATED, never silent.
 *
 * The count is the whole point: "Priya, Anil, Ravi and 14 others" and "Priya, Anil, Ravi" are
 * different facts about the same button, and only the first one is true.
 */
export function nameList(names: string[], max = NAMES_IN_PROSE): string {
  if (names.length <= max) return names.join(", ");
  return `${names.slice(0, max).join(", ")} and ${plural(names.length - max, "other")}`;
}
