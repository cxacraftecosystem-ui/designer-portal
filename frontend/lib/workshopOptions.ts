import {
  CAP_HINT_WITHOUT_SEARCH,
  CAP_HINT_WITH_SEARCH,
  RENDER_CAP,
  SEARCHING_LABEL,
  truncationSentence,
  type SelectOption
} from "@/components/ui/selectFilter";
import { formatDate } from "@/lib/format";
import { workshopDisplayTitle, type WorkshopTitleFields } from "@/lib/workshopTitle";

export type WorkshopTable = "design" | "field";

function nounFor(voice: { table: WorkshopTable; noun?: string }): string {
  if (voice.noun) return voice.noun;
  return voice.table === "design" ? "design workshops" : "workshops";
}

export const NO_DESIGN_WORKSHOP = "Not filed under a design workshop";

export const NO_FIELD_WORKSHOP = "Not linked to a workshop";

export const ATTACH_LATER = "Don't attach it yet";

export const TYPE_DETAILS_INSTEAD = "Do not link a workshop — type the details below";

export const GROUP_ON_THIS_RECORD = "Already on this record";

export const GROUP_OPEN = "Open";

export const GROUP_SUBMITTED_AND_ARCHIVED = "Submitted and archived";

export const GROUP_ENDED = "Ended";


export const WORKSHOP_OPTION_PAGE_SIZE = RENDER_CAP;

export type DesignWorkshopRow = {
  id: string;
  title: string;
  status: string;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  startDate: string | null;
  createdAt: string | null;
  deletedAt?: string | null;
};

export type FieldWorkshopRow = {
  id: string;
  title: string;
  place?: string | null;
  date?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  createdAt?: string | null;
};

export type OffPageIntent<Row> =
  | { mode: "refuse" }
  | { mode: "recover"; row: Row | null };

export type WorkshopListState<Row> =
  | { kind: "loading" }
  | { kind: "failed" }
  | {
      kind: "ok";
      rows: readonly Row[];
      total: number | null;
      truncated?: boolean;
      cachedAt?: string | null;
    };

export type WorkshopOptionSet = {
  options: SelectOption[];
  drawn: number;
  total: number | null;
  truncated: boolean;
  cut: number;
  recovered: boolean;
};

function present(value: string | null | undefined): string {
  return typeof value === "string" ? value.trim() : "";
}

function firstPresent(...values: Array<string | null | undefined>): string {
  for (const value of values) {
    const trimmed = present(value);
    if (trimmed) return trimmed;
  }
  return "";
}

function day(iso: string | null | undefined): string {
  const trimmed = present(iso);
  return trimmed ? trimmed.slice(0, 10) : "";
}

function labelFor(row: WorkshopTitleFields): string {
  return workshopDisplayTitle(row);
}

function hintFrom(parts: Array<string | null | undefined>): string | undefined {
  const joined = parts.map(present).filter(Boolean).join(" · ");
  return joined || undefined;
}

type Classified = {
  option: SelectOption;
  heading: string;
  occurrence: string;
  title: string;
  id: string;
};

function byOccurrenceThenTitleThenId(a: Classified, b: Classified): number {
  return (
    b.occurrence.localeCompare(a.occurrence) ||
    a.title.localeCompare(b.title) ||
    a.id.localeCompare(b.id)
  );
}

function assemble(entries: Classified[], order: readonly string[], group: boolean): SelectOption[] {
  const headings = order.filter((heading) => entries.some((entry) => entry.heading === heading));
  const applyHeadings = group && headings.length > 1;
  const options: SelectOption[] = [];
  for (const heading of headings) {
    const bucket = entries.filter((entry) => entry.heading === heading).sort(byOccurrenceThenTitleThenId);
    for (const entry of bucket) {
      options.push(applyHeadings ? { ...entry.option, group: heading } : entry.option);
    }
  }
  return options.length > RENDER_CAP ? options.slice(0, RENDER_CAP) : options;
}

function usable(row: { id: string }): boolean {
  return present(row.id) !== "";
}

export type WorkshopBuildOptions<Row> = {
  group: boolean;
  offPage: OffPageIntent<Row>;
};

function cutOf<Row>(
  state: WorkshopListState<Row>,
  drawn: number
): Pick<WorkshopOptionSet, "total" | "truncated" | "cut"> {
  if (state.kind !== "ok") return { total: null, truncated: false, cut: 0 };
  const total = state.total;
  const cut = total == null ? 0 : Math.max(0, total - drawn);
  return { total, truncated: Boolean(state.truncated) || cut > 0, cut };
}

export function designWorkshopOptions<Row extends DesignWorkshopRow>(
  state: WorkshopListState<Row>,
  opts: WorkshopBuildOptions<Row>
): WorkshopOptionSet {
  const rows: readonly Row[] = state.kind === "ok" ? state.rows : [];
  const listed = rows.filter((row) => usable(row) && !present(row.deletedAt));

  const classify = (row: Row, heading: string): Classified => {
    const status = present(row.status).toUpperCase();
    const statusWord =
      heading === GROUP_ON_THIS_RECORD
        ?
          ""
        : status === "SUBMITTED"
          ? "Submitted"
          : status === "ARCHIVED"
            ? "Archived"
            : status === "APPROVED"
              ? "Approved"
              : "";
    return {
      option: {
        value: row.id,
        label: labelFor(row),
        hint: hintFrom([statusWord, row.craftName, firstPresent(row.clusterName, row.state), day(row.startDate)])
      },
      heading,
      occurrence: firstPresent(row.startDate, row.createdAt),
      title: labelFor(row),
      id: row.id
    };
  };

  const entries: Classified[] = listed.map((row) => {
    const status = present(row.status).toUpperCase();
    const closed = status === "SUBMITTED" || status === "ARCHIVED" || status === "APPROVED";
    return classify(row, closed ? GROUP_SUBMITTED_AND_ARCHIVED : GROUP_OPEN);
  });

  const stored = opts.offPage.mode === "recover" ? opts.offPage.row : null;
  const recovered = Boolean(stored && usable(stored) && !listed.some((row) => row.id === stored.id));
  if (stored && recovered) entries.push(classify(stored, GROUP_ON_THIS_RECORD));

  const options = assemble(
    entries,
    [GROUP_ON_THIS_RECORD, GROUP_OPEN, GROUP_SUBMITTED_AND_ARCHIVED],
    opts.group
  );
  const drawn = options.length - (recovered ? 1 : 0);
  return { options, drawn, recovered, ...cutOf(state, drawn) };
}

export function fieldWorkshopOptions<Row extends FieldWorkshopRow>(
  state: WorkshopListState<Row>,
  opts: WorkshopBuildOptions<Row> & {
    now?: number;
  }
): WorkshopOptionSet {
  const rows: readonly Row[] = state.kind === "ok" ? state.rows : [];
  const listed = rows.filter(usable);
  const now = opts.now ?? Date.now();

  const classify = (row: Row, heading: string): Classified => {
    const ended = heading === GROUP_ENDED;
    return {
      option: {
        value: row.id,
        label: labelFor(row),
        hint: hintFrom([
          ended ? "Ended" : "",
          row.place,
          day(firstPresent(row.startDate, row.date, row.createdAt))
        ])
      },
      heading,
      occurrence: firstPresent(row.startDate, row.date, row.createdAt),
      title: labelFor(row),
      id: row.id
    };
  };

  const entries: Classified[] = listed.map((row) =>
    classify(row, hasEnded(row.endDate, now) ? GROUP_ENDED : GROUP_OPEN)
  );

  const stored = opts.offPage.mode === "recover" ? opts.offPage.row : null;
  const recovered = Boolean(stored && usable(stored) && !listed.some((row) => row.id === stored.id));
  if (stored && recovered) entries.push(classify(stored, GROUP_ON_THIS_RECORD));

  const options = assemble(entries, [GROUP_ON_THIS_RECORD, GROUP_OPEN, GROUP_ENDED], opts.group);
  const drawn = options.length - (recovered ? 1 : 0);
  return { options, drawn, recovered, ...cutOf(state, drawn) };
}

function hasEnded(endDate: string | null | undefined, now: number): boolean {
  const raw = present(endDate);
  if (!raw) return false;
  const end = new Date(raw);
  if (Number.isNaN(end.getTime())) return false;
  return now >= end.getTime() + 24 * 60 * 60 * 1000;
}

export function workshopCutSentence(
  set: Pick<WorkshopOptionSet, "drawn" | "total" | "truncated" | "cut" | "recovered">,
  opts: { term?: string; searchable: boolean }
): string {
  return truncationSentence({
    shown: set.drawn,
    pinned: set.recovered ? 1 : 0,
    total: set.total ?? set.drawn,
    capped: set.cut,
    term: opts.term ?? "",
    hint: opts.searchable ? CAP_HINT_WITH_SEARCH : CAP_HINT_WITHOUT_SEARCH,
    serverTruncated: set.truncated && set.cut === 0
  });
}

export type WorkshopListVoice = {
  table: WorkshopTable;
  noun?: string;
  accessList?: boolean;
  reassurance?: string;
  scoped: boolean;
  online: boolean;
  cached?: boolean;
};

export function deviceLooksOffline(): boolean {
  return typeof navigator !== "undefined" && navigator.onLine === false;
}

export function workshopListNotice<Row>(state: WorkshopListState<Row>, voice: WorkshopListVoice): string {
  const noun = nounFor(voice);
  if (state.kind === "loading") return "";
  if (state.kind === "failed") {
    return voice.online
      ? `The ${noun} list could not be loaded, so this is not showing what exists. ${
          voice.reassurance ?? "Nothing you have entered is at risk — this record can be saved without it."
        }`
      : voice.accessList === false
        ? `This device has not received the ${noun} list yet, so there is nothing to pick here. ${
            voice.cached
              ? "Connect once and the list is kept on the device from then on."
              : "Connect and it will load."
          }`
        : `This device has not received the ${noun} list yet, so there is nothing to pick here. Connect and it will load; this list is never kept on the device, so it always shows current access.`;
  }
  if (state.cachedAt && state.rows.length > 0 && voice.accessList === false) {
    return cachedListLine(state.total ?? state.rows.length, noun, formatDate(state.cachedAt));
  }
  if (state.rows.length > 0) return "";
  return genuinelyEmpty(voice);
}

export function cachedListLine(count: number, noun: string, refreshedOn: string): string {
  return (
    `${count} ${noun} on this device, last refreshed ${refreshedOn}. If the one you want is missing, ` +
    "refresh with a connection before concluding it is not on record."
  );
}

function genuinelyEmpty(voice: WorkshopListVoice): string {
  const noun = nounFor(voice);
  return voice.scoped
    ? `No ${noun} are open to this account. An administrator can give you access to one.`
    : `No ${noun} have been recorded yet.`;
}

export function workshopEmptyLabel<Row>(state: WorkshopListState<Row>, voice: WorkshopListVoice): string {
  if (state.kind === "loading") return SEARCHING_LABEL;
  return workshopListNotice(state, voice) || genuinelyEmpty(voice);
}

export function workshopListStandsDown(set: Pick<WorkshopOptionSet, "options">): boolean {
  return set.options.length === 0;
}
