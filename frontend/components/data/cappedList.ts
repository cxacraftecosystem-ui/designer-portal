import type { PageResult } from "@/lib/types";

export const LIST_PAGE_CEILING = 100;

export type ListCut = {
  noun: string;
  loaded: number;
  total: number;
};

export function listCut<T>(result: PageResult<T>, noun: string): ListCut | null {
  return cutOf(result.items.length, result.total, noun);
}

export function cutOf(loaded: number, total: number, noun: string): ListCut | null {
  const known = Number.isFinite(total) ? total : loaded;
  if (known <= loaded) return null;
  return { noun, loaded, total: known };
}

export type CutReach = "none" | "pager" | "search";

export function cappedListNotice(cut: ListCut | null, reach: CutReach = "none"): string {
  if (!cut) return "";
  if (cut.loaded === 0) {
    return `None of the ${cut.total} ${cut.noun} could be listed here, but they do exist.`;
  }
  if (reach === "pager") {
    return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — use the pager to reach the rest, which are not searched by the box above.`;
  }
  if (reach === "search") {
    return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — type in the box above to reach the rest; the search covers all ${cut.total}.`;
  }
  return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — the other ${cut.total - cut.loaded} are not on this list, and typing here searches only the ${cut.loaded} shown.`;
}

export function searchCutNotice({
  noun,
  loaded,
  total,
  term,
  emptyLabel = "",
  pending = false
}: {
  noun: string;
  loaded: number;
  total: number;
  term: string;
  emptyLabel?: string;
  pending?: boolean;
}): string {
  if (pending) return "";
  const trimmed = term.trim();
  const cut = cutOf(loaded, total, noun);
  if (cut && cut.loaded === 0) return cappedListNotice(cut);
  if (cut) {
    if (trimmed) {
      return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} matching “${trimmed}” — narrow the search above to reach the rest.`;
    }
    return cappedListNotice(cut, "search");
  }
  if (loaded > 0) return "";
  if (trimmed) {
    return `No ${noun} match “${trimmed}” anywhere in the full list. Clear the box to see the list again.`;
  }
  return emptyLabel;
}

export const CUT_NOTICE_LIVE_REGION = {
  role: "status",
  "aria-live": "polite"
} as const;

export function flagCutNotice(truncated: boolean | undefined, noun: string, term: string): string {
  if (!truncated) return "";
  if (term.trim()) {
    return `More ${noun} match “${term.trim()}” than this list can hold, so some are not shown — narrow the search above.`;
  }
  return `There are more ${noun} than this list can hold, so some are not shown — search for a name above to reach them. The box in this picker only filters what is already listed.`;
}

export function queuePageNotice(shown: number, total: number | undefined, more: boolean | undefined): string {
  if (!more) return "";
  const whole = Number.isFinite(total) && (total as number) > shown ? (total as number) : null;
  return whole === null ? `Showing the newest ${shown} waiting.` : `Showing ${shown} of ${whole} waiting, newest first.`;
}

export function mergeById<T extends { id: string }>(previous: readonly T[], incoming: readonly T[]): T[] {
  if (incoming.length === 0) return previous as T[];
  const seen = new Set(previous.map((row) => row.id));
  const added = incoming.filter((row) => !seen.has(row.id));
  return added.length === 0 ? (previous as T[]) : [...previous, ...added];
}
