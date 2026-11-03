import {
  EMPTY_SEARCH_FILTERS,
  RANGE_IDS,
  resolveRange,
  type RangeId
} from "@/components/search/SearchFilters";
import { ROLE_LABELS, ROLES_BY_RANK } from "@/lib/permissions";
import type { UserRole } from "@/lib/types";

export type RosterKind = "access" | "designer";

export type RosterDir = "asc" | "desc";

export const ADMIT_ROLE_DEFAULT = "default";

export const ROLE_NEVER_SIGNED_IN = "never-signed-in";

export const INSTITUTION_NONE = "none";

export const ACCESS_STATUS_TOKENS = ["ACTIVE", "PENDING", "REJECTED", "SUSPENDED"] as const;
export type AccessStatusToken = (typeof ACCESS_STATUS_TOKENS)[number];

export const DESIGNER_STANDING_TOKENS = ["active", "suspended"] as const;
export type DesignerStandingToken = (typeof DESIGNER_STANDING_TOKENS)[number];

export const ACCESS_DATE_FIELDS = ["added", "requested", "decided", "joined", "firstSeen"] as const;
export type AccessDateField = (typeof ACCESS_DATE_FIELDS)[number];

export const DESIGNER_DATE_FIELDS = ["added", "firstSeen", "revoked"] as const;
export type DesignerDateField = (typeof DESIGNER_DATE_FIELDS)[number];

export type RosterDateField = AccessDateField | DesignerDateField;

export type AccessSort =
  | "added"
  | "email"
  | "name"
  | "standing"
  | "joined"
  | "requested"
  | "decided"
  | "firstSeen"
  | "attempts";

export type DesignerSort = "added" | "email" | "name" | "institution" | "firstSeen" | "revoked";

export type RosterSort = AccessSort | DesignerSort;

export type SortValues = "date" | "text" | "count" | "enum";

export type RosterSortSpec = {
  defaultDir: RosterDir;
  values: SortValues;
  nullable: boolean;
};

export const ACCESS_SORTS: Record<AccessSort, RosterSortSpec> = {
  added: { defaultDir: "desc", values: "date", nullable: false },
  email: { defaultDir: "asc", values: "text", nullable: false },
  name: { defaultDir: "asc", values: "text", nullable: true },
  standing: { defaultDir: "asc", values: "enum", nullable: false },
  joined: { defaultDir: "desc", values: "date", nullable: true },
  requested: { defaultDir: "desc", values: "date", nullable: true },
  decided: { defaultDir: "desc", values: "date", nullable: true },
  firstSeen: { defaultDir: "desc", values: "date", nullable: true },
  attempts: { defaultDir: "desc", values: "count", nullable: false }
};

export const DESIGNER_SORTS: Record<DesignerSort, RosterSortSpec> = {
  added: { defaultDir: "desc", values: "date", nullable: false },
  email: { defaultDir: "asc", values: "text", nullable: false },
  name: { defaultDir: "asc", values: "text", nullable: true },
  institution: { defaultDir: "asc", values: "text", nullable: true },
  firstSeen: { defaultDir: "desc", values: "date", nullable: true },
  revoked: { defaultDir: "desc", values: "date", nullable: true }
};

export const ROSTER_DEFAULT_SORT: RosterSort = "added";
export const ROSTER_DEFAULT_DIR: RosterDir = "desc";

function sortTable(kind: RosterKind): Record<string, RosterSortSpec | undefined> {
  return kind === "access" ? ACCESS_SORTS : DESIGNER_SORTS;
}

export function rosterSortSpec(kind: RosterKind, column: string): RosterSortSpec | undefined {
  return sortTable(kind)[column];
}

export type RosterFilters = {
  search: string;
  status: AccessStatusToken[];
  standing: "" | DesignerStandingToken;
  roles: string[];
  institutions: string[];
  dateField: RosterDateField;
  range: RangeId;
  from: string;
  to: string;
  sort: RosterSort;
  dir: RosterDir;
};

export function emptyRosterFilters(kind: RosterKind): RosterFilters {
  return {
    search: "",
    status: [],
    standing: "",
    roles: [],
    institutions: [],
    dateField: (dateFieldOptions(kind)[0]?.value ?? "added") as RosterDateField,
    range: "any",
    from: "",
    to: "",
    sort: ROSTER_DEFAULT_SORT,
    dir: ROSTER_DEFAULT_DIR
  };
}

export const ROSTER_LABELS = {
  accessSearch: "Search the list by email, name or note",
  designerSearch: "Search the roster by email, name or institution",
  accessStatus: "Standing",
  accessRoles: "Role they join with",
  designerStanding: "Standing",
  designerRoles: "Role of the linked account",
  designerInstitutions: "Institution",
  dateField: "Which date",
  dateRange: "Date range",
  datePeriod: "Period",
  dateFrom: "From",
  dateTo: "To",
  clearAll: "Clear every filter"
} as const;

export const DESIGNER_ROLE_HINT =
  "Matched by email. Admins do not need to be on this roster, so filtering for Admin lists empanelled addresses that belong to admins.";

export const ACCESS_INSTITUTION_NOTE =
  "Institution is recorded on the designer roster, not on this list. Filter by it there.";

export type RosterOption = { value: string; label: string; hint?: string };

export const ACCESS_STATUS_OPTIONS: RosterOption[] = [
  { value: "ACTIVE", label: "May sign in" },
  { value: "PENDING", label: "Waiting for a decision" },
  { value: "REJECTED", label: "Refused" },
  { value: "SUSPENDED", label: "Suspended" }
];

export const DESIGNER_STANDING_OPTIONS: RosterOption[] = [
  { value: "", label: "Everyone ever empanelled" },
  { value: "active", label: "Only those who may sign in" },
  { value: "suspended", label: "Only those suspended" }
];

export function roleOptions(kind: RosterKind): RosterOption[] {
  const ladder = ROLES_BY_RANK.map((role: UserRole) => ({ value: role, label: ROLE_LABELS[role] }));
  return [
    ...ladder,
    kind === "access"
      ? {
          value: ADMIT_ROLE_DEFAULT,
          label: "With the default role",
          hint: "No role was chosen when this address was admitted"
        }
      : {
          value: ROLE_NEVER_SIGNED_IN,
          label: "Has never signed in",
          hint: "Empanelled, but no account has used this address yet"
        }
  ];
}

export function institutionOptions(names: readonly string[]): RosterOption[] {
  const seen = new Set<string>([INSTITUTION_NONE]);
  const rows: RosterOption[] = [];
  names.forEach((name) => {
    const value = name.trim();
    if (!value || seen.has(value)) return;
    seen.add(value);
    rows.push({ value, label: value });
  });
  rows.push({ value: INSTITUTION_NONE, label: "No institution recorded" });
  return rows;
}

export function dateFieldOptions(kind: RosterKind): RosterOption[] {
  return kind === "access"
    ? [
        { value: "added", label: "Added to the list" },
        { value: "requested", label: "Access requested" },
        { value: "decided", label: "Decision made" },
        { value: "joined", label: "Joined the platform" },
        { value: "firstSeen", label: "First signed in" }
      ]
    : [
        { value: "added", label: "Added to the roster" },
        { value: "firstSeen", label: "First signed in" },
        { value: "revoked", label: "Access revoked" }
      ];
}

const RANGE_LABELS: Record<RangeId, string> = {
  any: "Any time",
  today: "Today",
  "7d": "Last 7 days",
  "30d": "Last 30 days",
  "90d": "Last 90 days",
  month: "This month",
  year: "This year",
  custom: "Custom range"
};

export const RANGE_OPTIONS: RosterOption[] = RANGE_IDS.map((id) => ({
  value: id,
  label: RANGE_LABELS[id]
}));

const DIRECTION_PHRASE: Record<SortValues, Record<RosterDir, string>> = {
  date: { desc: "newest first", asc: "oldest first" },
  text: { asc: "A to Z", desc: "Z to A" },
  count: { desc: "most first", asc: "fewest first" },
  enum: { asc: "in standing order", desc: "in reverse standing order" }
};

export function sortDirectionPhrase(values: SortValues, dir: RosterDir): string {
  return DIRECTION_PHRASE[values][dir];
}

export function nextSortDir(kind: RosterKind, filters: RosterFilters, column: string): RosterDir {
  const spec = rosterSortSpec(kind, column);
  if (!spec) return filters.dir;
  if (filters.sort === column) return filters.dir === "asc" ? "desc" : "asc";
  return spec.defaultDir;
}

export function nextRosterSort(kind: RosterKind, filters: RosterFilters, column: string): RosterFilters {
  const spec = rosterSortSpec(kind, column);
  if (!spec) return filters;
  return { ...filters, sort: column as RosterSort, dir: nextSortDir(kind, filters, column) };
}

export function sortActionLabel(
  kind: RosterKind,
  filters: RosterFilters,
  column: string,
  label: string
): string {
  const spec = rosterSortSpec(kind, column);
  if (!spec) return label;
  const dir = nextSortDir(kind, filters, column);
  const action = `Sort by ${label}, ${sortDirectionPhrase(spec.values, dir)}`;
  return spec.nullable && dir === "desc" ? `${action} — entries with no date sort first` : action;
}

function tokenList(values: readonly string[], order: readonly string[]): string | undefined {
  const chosen = new Set(values.map((value) => value.trim()).filter(Boolean));
  if (chosen.size === 0) return undefined;
  const ordered = order.filter((token) => chosen.has(token));
  const extras = [...chosen]
    .filter((token) => !ordered.includes(token))
    .sort((a, b) => a.localeCompare(b));
  return [...ordered, ...extras].join(",");
}

function institutionList(values: readonly string[]): string | undefined {
  const chosen = new Set(values.map((value) => value.trim()).filter(Boolean));
  if (chosen.size === 0) return undefined;
  const named = [...chosen]
    .filter((value) => value !== INSTITUTION_NONE)
    .sort((a, b) => a.localeCompare(b));
  return [...named, ...(chosen.has(INSTITUTION_NONE) ? [INSTITUTION_NONE] : [])].join(",");
}

function roleOrder(kind: RosterKind): string[] {
  return [...ROLES_BY_RANK, kind === "access" ? ADMIT_ROLE_DEFAULT : ROLE_NEVER_SIGNED_IN];
}

export type RosterQueryParams = {
  search?: string;
  status?: string;
  standing?: string;
  roles?: string;
  institutions?: string;
  dateField?: string;
  dateFrom?: string;
  dateTo?: string;
  sort?: string;
  dir?: RosterDir;
};

export function rosterQueryParams(
  kind: RosterKind,
  filters: RosterFilters,
  now: Date = new Date()
): RosterQueryParams {
  const access = kind === "access";
  const bounds =
    filters.range === "any"
      ? {}
      : resolveRange(
          { ...EMPTY_SEARCH_FILTERS, range: filters.range, from: filters.from, to: filters.to },
          now
        );
  const dated = Boolean(bounds.dateFrom || bounds.dateTo);
  const ordered = filters.sort === ROSTER_DEFAULT_SORT && filters.dir === ROSTER_DEFAULT_DIR;

  return {
    search: filters.search.trim() || undefined,
    status: access ? tokenList(filters.status, ACCESS_STATUS_TOKENS) : undefined,
    standing: access ? undefined : filters.standing || undefined,
    roles: tokenList(filters.roles, roleOrder(kind)),
    institutions: access ? undefined : institutionList(filters.institutions),
    dateField: dated ? filters.dateField : undefined,
    dateFrom: dated ? bounds.dateFrom : undefined,
    dateTo: dated ? bounds.dateTo : undefined,
    sort: ordered ? undefined : filters.sort,
    dir: ordered ? undefined : filters.dir
  };
}

export function rosterLinkParams(
  kind: RosterKind,
  filters: RosterFilters
): Record<string, string | undefined> {
  const access = kind === "access";
  return {
    search: filters.search.trim() || undefined,
    status: access ? tokenList(filters.status, ACCESS_STATUS_TOKENS) : undefined,
    standing: access ? undefined : filters.standing || undefined,
    roles: tokenList(filters.roles, roleOrder(kind)),
    institutions: access ? undefined : institutionList(filters.institutions),
    dateField: filters.dateField === "added" ? undefined : filters.dateField,
    range: filters.range === "any" ? undefined : filters.range,
    from: filters.range === "custom" ? filters.from || undefined : undefined,
    to: filters.range === "custom" ? filters.to || undefined : undefined,
    sort: filters.sort === ROSTER_DEFAULT_SORT ? undefined : filters.sort,
    dir:
      filters.sort === ROSTER_DEFAULT_SORT && filters.dir === ROSTER_DEFAULT_DIR
        ? undefined
        : filters.dir
  };
}

function readTokens(params: URLSearchParams, key: string): string[] {
  const seen = new Set<string>();
  params.getAll(key).forEach((raw) => {
    raw
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean)
      .forEach((value) => seen.add(value));
  });
  return [...seen];
}

export function rosterFiltersFromSearchParams(
  kind: RosterKind,
  params: URLSearchParams
): RosterFilters {
  const base = emptyRosterFilters(kind);
  const access = kind === "access";

  const rangeParam = (params.get("range") ?? "").trim();
  const from = params.get("from") ?? "";
  const to = params.get("to") ?? "";
  const range: RangeId = (RANGE_IDS as readonly string[]).includes(rangeParam)
    ? (rangeParam as RangeId)
    : from || to
      ? "custom"
      : "any";

  const dateFieldParam = (params.get("dateField") ?? "").trim();
  const dateFields: readonly string[] = access ? ACCESS_DATE_FIELDS : DESIGNER_DATE_FIELDS;
  const dateField = (
    dateFields.includes(dateFieldParam) ? dateFieldParam : base.dateField
  ) as RosterDateField;

  const sortParam = (params.get("sort") ?? "").trim();
  const sort = (rosterSortSpec(kind, sortParam) ? sortParam : ROSTER_DEFAULT_SORT) as RosterSort;
  const dirParam = (params.get("dir") ?? "").trim();
  const dir: RosterDir =
    dirParam === "asc" || dirParam === "desc"
      ? dirParam
      : (rosterSortSpec(kind, sort)?.defaultDir ?? ROSTER_DEFAULT_DIR);

  const standingParam = (params.get("standing") ?? "").trim();
  const standing = (DESIGNER_STANDING_TOKENS as readonly string[]).includes(standingParam)
    ? (standingParam as DesignerStandingToken)
    : "";

  return {
    search: params.get("search") ?? "",
    status: access ? (readTokens(params, "status") as AccessStatusToken[]) : [],
    standing: access ? "" : standing,
    roles: readTokens(params, "roles"),
    institutions: access ? [] : readTokens(params, "institutions"),
    dateField,
    range,
    from,
    to,
    sort,
    dir
  };
}

export function hasActiveRosterFilters(kind: RosterKind, filters: RosterFilters): boolean {
  const access = kind === "access";
  return Boolean(
    filters.search.trim() ||
      (access && filters.status.length > 0) ||
      (!access && filters.standing) ||
      filters.roles.length > 0 ||
      (!access && filters.institutions.length > 0) ||
      filters.range !== "any"
  );
}

export function clearRosterFilters(kind: RosterKind, filters: RosterFilters): RosterFilters {
  return { ...emptyRosterFilters(kind), sort: filters.sort, dir: filters.dir };
}

export function institutionCutNotice(truncated: boolean | undefined, offered: number): string {
  if (!truncated) return "";
  if (offered <= 0) {
    return "Not every institution fits in this list. Type the institution into the search box above instead.";
  }
  return `Showing the first ${offered} institutions. Type an institution's name into the search box above to find the rest.`;
}

export function roleMatchCutNotice(truncated: boolean | undefined, limit?: number): string {
  if (!truncated) return "";
  const bound =
    typeof limit === "number" && Number.isFinite(limit) && limit > 0
      ? `more than ${limit} accounts`
      : "too many accounts";
  return `Some designers with the selected roles are missing from this list, because ${bound} have those roles. Choose fewer roles to see the complete list.`;
}
