export type SelectOption = {
  value: string;
  label: string;
  hint?: string;
  group?: string;
  disabled?: boolean;
};

export const SEARCH_THRESHOLD = 8;

export const RENDER_CAP = 80;

export const VIRTUALIZE_AT = 200;

export const OVERSCAN = 8;

export type VirtualSlice = { virtual: boolean; start: number; end: number; padTop: number; padBottom: number };

export function virtualSlice(
  count: number,
  scrollTop: number,
  viewport: number,
  rowHeight: number,
  flat = true
): VirtualSlice {
  if (!flat || count <= VIRTUALIZE_AT || rowHeight <= 0) {
    return { virtual: false, start: 0, end: count, padTop: 0, padBottom: 0 };
  }
  const start = Math.max(0, Math.min(count, Math.floor(scrollTop / rowHeight) - OVERSCAN));
  const end = Math.min(count, Math.ceil((scrollTop + viewport) / rowHeight) + OVERSCAN);
  return { virtual: true, start, end, padTop: start * rowHeight, padBottom: (count - end) * rowHeight };
}

export function scrollTopToShow(index: number, rowHeight: number, scrollTop: number, viewport: number): number {
  const top = index * rowHeight;
  if (top < scrollTop) return top;
  if (top + rowHeight > scrollTop + viewport) return top + rowHeight - viewport;
  return scrollTop;
}

export function fold(text: string) {
  return text
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

function rankWithin(hay: string, needle: string): number {
  const index = hay.indexOf(needle);
  if (index < 0) return -1;
  if (index === 0) return 0;
  return /[\s\-–—/(,.·]/.test(hay[index - 1]) ? 1 : 2;
}

export function filterOptions(options: SelectOption[], query: string): SelectOption[] {
  const needle = fold(query.trim());
  if (!needle) return options;
  const ranked: Array<{ option: SelectOption; rank: number; at: number }> = [];
  options.forEach((option, at) => {
    const onLabel = rankWithin(fold(option.label), needle);
    if (onLabel >= 0) {
      ranked.push({ option, rank: onLabel, at });
      return;
    }
    const onHint = option.hint ? rankWithin(fold(option.hint), needle) : -1;
    if (onHint >= 0) ranked.push({ option, rank: 3 + onHint, at });
  });
  ranked.sort((a, b) => a.rank - b.rank || a.at - b.at);
  return ranked.map((entry) => entry.option);
}

export function typeaheadIndex(options: SelectOption[], typed: string): number {
  const needle = fold(typed);
  if (!needle) return -1;
  return options.findIndex((option) => !option.disabled && fold(option.label).startsWith(needle));
}

export const CAP_HINT_WITH_SEARCH = "Keep typing to narrow the list.";

export const CAP_HINT_WITHOUT_SEARCH = "Narrow the list to see the rest.";

export function capNoticeSentence({
  shown,
  pinned,
  total,
  hint
}: {
  shown: number;
  pinned: number;
  total: number;
  hint: string;
}): string {
  const plus = pinned > 0 ? `, plus ${pinned} already selected` : "";
  return `Showing the first ${shown} of ${total}${plus}. ${hint}`;
}

export function unknownTotalNoticeSentence({
  shown,
  pinned,
  term,
  hint
}: {
  shown: number;
  pinned: number;
  term: string;
  hint: string;
}): string {
  const plus = pinned > 0 ? `, plus ${pinned} already selected` : "";
  const trimmed = term.trim();
  const more = trimmed
    ? `More match “${trimmed}” than are shown.`
    : `There are more than are shown.`;
  return `Showing the first ${shown}${plus}. ${more} ${hint}`;
}

export const SEARCHING_LABEL = "Searching…";

export function serverNoMatchSentence(term: string): string {
  return `No matches for “${term.trim()}” in the whole list.`;
}

export function listAnnouncement({
  total,
  matched,
  term,
  server,
  pending,
  truncated
}: {
  total: number;
  matched: number;
  term: string;
  server: boolean;
  pending: boolean;
  truncated: boolean;
}): string {
  const trimmed = term.trim();
  if (server && pending) return trimmed ? `Searching for ${trimmed}` : "Loading options";
  if (server) {
    const more = truncated
      ? trimmed
        ? ", and more match than are shown"
        : ", and more exist than are shown"
      : "";
    return trimmed ? `${total} options match ${trimmed}${more}` : `${total} options${more}`;
  }
  if (trimmed) return `${matched} of ${total} options match ${trimmed}`;
  return `${total} options`;
}

export const SUMMARY_NAMES = 6;

export function selectionSummarySentence({
  selected,
  names
}: {
  selected: number;
  names: string[];
}): string {
  if (selected <= 0) return "Nothing selected";
  if (names.length === selected && selected <= SUMMARY_NAMES) {
    return `${selected} selected: ${names.join(", ")}`;
  }
  if (!names.length) return `${selected} selected`;
  return `${selected} selected, including ${names.slice(0, SUMMARY_NAMES).join(", ")}`;
}

export function emptyListSentence({
  emptyLabel,
  term,
  server,
  pending
}: {
  emptyLabel: string;
  term: string;
  server: boolean;
  pending: boolean;
}): string {
  if (server && pending) return SEARCHING_LABEL;
  if (!term.trim()) return emptyLabel;
  return server ? serverNoMatchSentence(term) : "No matches";
}

export function truncationSentence({
  shown,
  pinned,
  total,
  capped,
  term,
  hint,
  serverTruncated
}: {
  shown: number;
  pinned: number;
  total: number;
  capped: number;
  term: string;
  hint: string;
  serverTruncated: boolean;
}): string {
  if (shown <= 0) return "";
  if (serverTruncated) return unknownTotalNoticeSentence({ shown, pinned, term, hint });
  if (capped > 0) return capNoticeSentence({ shown, pinned, total, hint });
  return "";
}

export type OptionGroup = {
  group: string | null;
  rows: Array<{ option: SelectOption; index: number }>;
};

export function groupRows(rendered: SelectOption[]): OptionGroup[] {
  if (!rendered.some((option) => option.group)) {
    return [{ group: null, rows: rendered.map((option, index) => ({ option, index })) }];
  }
  const buckets: OptionGroup[] = [];
  const at = new Map<string | null, OptionGroup>();
  rendered.forEach((option, index) => {
    const key = option.group ?? null;
    let bucket = at.get(key);
    if (!bucket) {
      bucket = { group: key, rows: [] };
      at.set(key, bucket);
      buckets.push(bucket);
    }
    bucket.rows.push({ option, index });
  });
  return buckets.sort((a, b) => (a.group === null ? -1 : b.group === null ? 1 : 0));
}
