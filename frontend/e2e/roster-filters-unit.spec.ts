import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  ACCESS_DATE_FIELDS,
  ACCESS_SORTS,
  ACCESS_STATUS_OPTIONS,
  ACCESS_STATUS_TOKENS,
  ADMIT_ROLE_DEFAULT,
  DESIGNER_SORTS,
  DESIGNER_STANDING_OPTIONS,
  INSTITUTION_NONE,
  ROLE_NEVER_SIGNED_IN,
  ROSTER_LABELS,
  clearRosterFilters,
  dateFieldOptions,
  emptyRosterFilters,
  hasActiveRosterFilters,
  institutionCutNotice,
  institutionOptions,
  nextRosterSort,
  roleMatchCutNotice,
  roleOptions,
  rosterFiltersFromSearchParams,
  rosterLinkParams,
  rosterQueryParams,
  rosterSortSpec,
  sortActionLabel,
  type RosterFilters,
  type RosterKind
} from "@/components/admin/rosterFilters";
import { buildQuery } from "@/lib/api";
import { ROLES_BY_RANK } from "@/lib/permissions";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const BAR = read("components", "admin", "RosterFilterBar.tsx");
const SORTABLE = read("components", "admin", "SortableTh.tsx");
const MODULE = read("components", "admin", "rosterFilters.ts");

const ADMIN_DIR = ["app", "(protected)", "admin"] as const;
const ACCESS_PAGE = read(...ADMIN_DIR, "access", "page.tsx");
const DESIGNER_PAGE = read(...ADMIN_DIR, "designers", "page.tsx");

const PAGES: { kind: RosterKind; name: string; source: string }[] = [
  { kind: "access", name: "admin/access/page.tsx", source: ACCESS_PAGE },
  { kind: "designer", name: "admin/designers/page.tsx", source: DESIGNER_PAGE }
];

function sourcesUnder(...parts: string[]): { name: string; source: string }[] {
  const root = join(__dirname, "..", ...parts);
  const found: { name: string; source: string }[] = [];
  const walk = (dir: string, prefix: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const child = join(dir, entry.name);
      const label = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isDirectory()) walk(child, label);
      else if (/\.tsx?$/.test(entry.name)) found.push({ name: label, source: readFileSync(child, "utf8") });
    }
  };
  walk(root, parts[parts.length - 1] ?? "");
  return found;
}

function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/.*$/gm, "$1");
}

function multiSelects(source: string): string[] {
  return [...source.matchAll(/<MultiSelectDropdown\b[\s\S]*?\/>/g)].map((match) => match[0]);
}

function singleSelects(source: string): string[] {
  return [...source.matchAll(/<Dropdown\b[\s\S]*?\/>/g)].map((match) => match[0]);
}

const KINDS: RosterKind[] = ["access", "designer"];

test("filters send nothing when nothing is chosen", () => {
  for (const kind of KINDS) {
    const params = rosterQueryParams(kind, emptyRosterFilters(kind));
    expect(params.roles, `${kind}: roles`).toBeUndefined();
    expect(params.status, `${kind}: status`).toBeUndefined();
    expect(params.institutions, `${kind}: institutions`).toBeUndefined();
    expect(params.standing, `${kind}: standing`).toBeUndefined();
    expect(params.search, `${kind}: search`).toBeUndefined();
    expect(params.dateField, `${kind}: dateField`).toBeUndefined();
    expect(params.dateFrom, `${kind}: dateFrom`).toBeUndefined();
    expect(params.dateTo, `${kind}: dateTo`).toBeUndefined();
    expect(params.sort, `${kind}: sort`).toBeUndefined();
    expect(params.dir, `${kind}: dir`).toBeUndefined();
    expect(Object.values(params).every((value) => value === undefined)).toBe(true);
  }
});

test("all eight ticked is not the same request as none ticked", () => {
  const none = rosterQueryParams("access", emptyRosterFilters("access"));
  const all = rosterQueryParams("access", { ...emptyRosterFilters("access"), roles: [...ROLES_BY_RANK] });
  expect(none.roles).toBeUndefined();
  expect(all.roles).toBe(ROLES_BY_RANK.join(","));
  expect(all.roles).not.toBe(none.roles);

  const withDefaultTier = rosterQueryParams("access", {
    ...emptyRosterFilters("access"),
    roles: [...ROLES_BY_RANK, ADMIT_ROLE_DEFAULT]
  });
  expect(withDefaultTier.roles).not.toBe(all.roles);
  expect(withDefaultTier.roles?.endsWith(ADMIT_ROLE_DEFAULT)).toBe(true);
});

test("empty and all-blank token lists do not filter", () => {
  const base = emptyRosterFilters("designer");
  expect(rosterQueryParams("designer", { ...base, roles: [] }).roles).toBeUndefined();
  expect(rosterQueryParams("designer", { ...base, roles: ["", "  "] }).roles).toBeUndefined();
  expect(rosterQueryParams("designer", { ...base, institutions: ["", " "] }).institutions).toBeUndefined();
});

test("the same ticks in a different order build the same query string", () => {
  const base = emptyRosterFilters("access");
  const a = rosterQueryParams("access", { ...base, status: ["SUSPENDED", "ACTIVE"] });
  const b = rosterQueryParams("access", { ...base, status: ["ACTIVE", "SUSPENDED"] });
  expect(a.status).toBe(b.status);
  expect(a.status).toBe("ACTIVE,SUSPENDED");
});

test("every filter multi-select passes bulk={false} and confirmOnSelect={false}", () => {
  const controls = multiSelects(BAR);
  expect(controls.length).toBeGreaterThanOrEqual(2);
  for (const control of controls) {
    expect(control, control.slice(0, 80)).toContain("bulk={false}");
    expect(control, control.slice(0, 80)).toContain("confirmOnSelect={false}");
  }
});

test("every single-select that filters the screen passes advanceOnSelect={false}", () => {
  const controls = singleSelects(BAR);
  expect(controls.length).toBeGreaterThanOrEqual(3);
  for (const control of controls) {
    expect(control, control.slice(0, 80)).toContain("advanceOnSelect={false}");
  }
});

test("the role filter offers the whole ladder and never assignableRoles", () => {
  for (const kind of KINDS) {
    const values = roleOptions(kind).map((option) => option.value);
    for (const role of ROLES_BY_RANK) expect(values).toContain(role);
    expect(values).toHaveLength(ROLES_BY_RANK.length + 1);
    expect(values.at(-1)).toBe(kind === "access" ? ADMIT_ROLE_DEFAULT : ROLE_NEVER_SIGNED_IN);
  }
  expect(withoutComments(MODULE)).not.toContain("assignableRoles");
  expect(withoutComments(BAR)).not.toContain("assignableRoles");
});

test("the institution picker carries the reserved no-institution row, last and once", () => {
  const rows = institutionOptions(["Kalaraksha", "NID", "Kalaraksha"]);
  expect(rows.map((row) => row.value)).toEqual(["Kalaraksha", "NID", INSTITUTION_NONE]);
  const collided = institutionOptions(["none", "NID"]);
  expect(collided.filter((row) => row.value === INSTITUTION_NONE)).toHaveLength(1);
  expect(collided.at(-1)?.value).toBe(INSTITUTION_NONE);
});

test("no filter control defaults to a narrowing value", () => {
  for (const kind of KINDS) {
    const base = emptyRosterFilters(kind);
    expect(base.status).toEqual([]);
    expect(base.standing).toBe("");
    expect(base.roles).toEqual([]);
    expect(base.institutions).toEqual([]);
    expect(base.search).toBe("");
    expect(base.range).toBe("any");
    expect(hasActiveRosterFilters(kind, base)).toBe(false);
  }
});

test("the access standing filter offers refused and suspended, and excludes neither by default", () => {
  const values = ACCESS_STATUS_OPTIONS.map((option) => option.value);
  expect(values).toEqual([...ACCESS_STATUS_TOKENS]);
  expect(values).toContain("REJECTED");
  expect(values).toContain("SUSPENDED");
  expect(rosterQueryParams("access", emptyRosterFilters("access")).status).toBeUndefined();
});

test("there is no hide-suspended control on either roster", () => {
  const source = withoutComments(BAR) + withoutComments(MODULE);
  expect(source.toLowerCase()).not.toContain("hidesuspended");
  expect(source.toLowerCase()).not.toContain("hide suspended");
  expect(source.toLowerCase()).not.toContain("excludesuspended");
});

test("the designer standing filter opens on everyone and can ask for the suspended alone", () => {
  const values = DESIGNER_STANDING_OPTIONS.map((option) => option.value);
  expect(values[0]).toBe("");
  expect(values).toEqual(["", "active", "suspended"]);
  const base = emptyRosterFilters("designer");
  expect(rosterQueryParams("designer", base).standing).toBeUndefined();
  expect(rosterQueryParams("designer", { ...base, standing: "suspended" }).standing).toBe("suspended");
});

test("activeOnly is never emitted beside standing", () => {
  const params = rosterQueryParams("designer", { ...emptyRosterFilters("designer"), standing: "active" });
  expect(Object.keys(params)).not.toContain("activeOnly");
  expect(withoutComments(MODULE)).not.toContain("activeOnly");
});

test("an untruncated read says nothing at all", () => {
  expect(institutionCutNotice(undefined, 200)).toBe("");
  expect(institutionCutNotice(false, 200)).toBe("");
  expect(roleMatchCutNotice(undefined)).toBe("");
  expect(roleMatchCutNotice(false, 50000)).toBe("");
});

test("a truncated institution vocabulary states the number it was handed", () => {
  const notice = institutionCutNotice(true, 200);
  expect(notice).toContain("200");
  expect(notice).toContain("search box above");
  expect(notice).not.toContain("this picker");
  expect(institutionCutNotice(true, 0)).not.toBe("");
});

test("a truncated role match is stated, and never tells the reader to narrow the search", () => {
  const notice = roleMatchCutNotice(true, 50000);
  expect(notice).toContain("50000");
  expect(notice).toContain("missing");
  expect(notice).not.toContain("narrow the search");
  expect(notice).toContain("fewer roles");
  const unnumbered = roleMatchCutNotice(true);
  expect(unnumbered).not.toBe("");
  expect(unnumbered).not.toMatch(/\d/);
});

test("both cut notices are drawn inside a live region that is mounted before it speaks", () => {
  const regions = [...BAR.matchAll(/CUT_NOTICE_LIVE_REGION/g)];
  expect(regions.length).toBeGreaterThanOrEqual(2);
  expect(BAR).toContain("<CappedListNotice id={roleCutId}");
  expect(BAR).toContain("<CappedListNotice id={institutionCutId}");
});

test("the filter bar filters no array", () => {
  const source = withoutComments(BAR);
  expect(source).not.toMatch(/\brows\.filter\(/);
  expect(source).not.toMatch(/\bitems\.filter\(/);
  expect(source).not.toMatch(/\bdata\.items\b/);
  expect(source).not.toContain("apiFetch");
  expect(source).not.toContain("listResource");
});

test("the sort control hands the page new filters and sorts nothing itself", () => {
  const source = withoutComments(SORTABLE);
  expect(source).toContain("nextRosterSort");
  expect(source).not.toContain(".sort(");
  expect(source).not.toContain("sortedWith");
  expect(source).not.toContain("localeCompare");
});

test("one date range per request, over one named column", () => {
  for (const kind of KINDS) {
    const fields = dateFieldOptions(kind).map((option) => option.value);
    expect(fields.length).toBeGreaterThan(0);
    expect(fields[0]).toBe("added");
  }
  expect(dateFieldOptions("access").map((option) => option.value)).toEqual([...ACCESS_DATE_FIELDS]);
  const params = rosterQueryParams("access", {
    ...emptyRosterFilters("access"),
    dateField: "requested",
    range: "30d"
  });
  expect(Object.keys(params).filter((key) => key.startsWith("date"))).toEqual([
    "dateField",
    "dateFrom",
    "dateTo"
  ]);
  expect(params.dateField).toBe("requested");
});

test("a range of any time sends no date keys, and neither does an empty custom range", () => {
  const base = emptyRosterFilters("designer");
  const any = rosterQueryParams("designer", { ...base, dateField: "firstSeen", range: "any" });
  expect(any.dateField).toBeUndefined();
  expect(any.dateFrom).toBeUndefined();
  expect(any.dateTo).toBeUndefined();

  const blank = rosterQueryParams("designer", { ...base, range: "custom", from: "", to: "" });
  expect(blank.dateField).toBeUndefined();
  expect(blank.dateFrom).toBeUndefined();
});

test("presets resolve on the reader's clock, in local time", () => {
  const now = new Date(2026, 6, 20, 12, 0, 0);
  const params = rosterQueryParams(
    "access",
    { ...emptyRosterFilters("access"), dateField: "joined", range: "30d" },
    now
  );
  expect(new Date(params.dateFrom as string).getTime()).toBe(new Date(2026, 5, 21).getTime());
  expect(new Date(params.dateTo as string).getTime()).toBe(
    new Date(2026, 6, 20, 23, 59, 59, 999).getTime()
  );
});

test("a preset resolves at request time, not at pick time", () => {
  const filters: RosterFilters = { ...emptyRosterFilters("access"), range: "30d" };
  const monday = rosterQueryParams("access", filters, new Date(2026, 6, 20, 12));
  const tuesday = rosterQueryParams("access", filters, new Date(2026, 6, 21, 12));
  expect(monday.dateFrom).not.toBe(tuesday.dateFrom);
  expect(monday.dateTo).not.toBe(tuesday.dateTo);
});

test("a custom range covers the whole of its end day", () => {
  const params = rosterQueryParams("designer", {
    ...emptyRosterFilters("designer"),
    dateField: "revoked",
    range: "custom",
    from: "2026-07-01",
    to: "2026-07-31"
  });
  expect(new Date(params.dateFrom as string).getTime()).toBe(new Date(2026, 6, 1).getTime());
  expect(new Date(params.dateTo as string).getTime()).toBe(
    new Date(2026, 6, 31, 23, 59, 59, 999).getTime()
  );
});

test("each roster offers exactly the sort columns its route has, and no others", () => {
  expect(Object.keys(ACCESS_SORTS)).toEqual([
    "added",
    "email",
    "name",
    "standing",
    "joined",
    "requested",
    "decided",
    "firstSeen",
    "attempts"
  ]);
  expect(Object.keys(DESIGNER_SORTS)).toEqual([
    "added",
    "email",
    "name",
    "institution",
    "firstSeen",
    "revoked"
  ]);
  for (const [kind, table] of [
    ["access", ACCESS_SORTS],
    ["designer", DESIGNER_SORTS]
  ] as const) {
    for (const column of Object.keys(table)) {
      const params = rosterQueryParams(kind, {
        ...emptyRosterFilters(kind),
        sort: column as never,
        dir: "asc"
      });
      expect(params.sort, `${kind}/${column}`).toBe(column);
      expect(params.dir, `${kind}/${column}`).toBe("asc");
    }
  }
});

test("the server's default order is the one pair left off the wire", () => {
  for (const kind of KINDS) {
    const base = emptyRosterFilters(kind);
    expect(base.sort).toBe("added");
    expect(base.dir).toBe("desc");
    const params = rosterQueryParams(kind, base);
    expect(params.sort).toBeUndefined();
    expect(params.dir).toBeUndefined();
  }
});

test("any other order is sent as a pair, never as a lone sort key", () => {
  const params = rosterQueryParams("access", { ...emptyRosterFilters("access"), sort: "email", dir: "asc" });
  expect(params.sort).toBe("email");
  expect(params.dir).toBe("asc");
});

test("clicking a new column takes that column's own first reading, not the last one's direction", () => {
  const base = emptyRosterFilters("access");
  const byEmail = nextRosterSort("access", base, "email");
  expect(byEmail.sort).toBe("email");
  expect(byEmail.dir).toBe("asc");

  const flipped = nextRosterSort("access", byEmail, "email");
  expect(flipped.dir).toBe("desc");

  const byRequested = nextRosterSort("access", flipped, "requested");
  expect(byRequested.sort).toBe("requested");
  expect(byRequested.dir).toBe("desc");
});

test("a sort token that does not belong to a roster changes nothing", () => {
  const base = emptyRosterFilters("designer");
  expect(rosterSortSpec("designer", "attempts")).toBeUndefined();
  expect(nextRosterSort("designer", base, "attempts")).toEqual(base);
});

test("the sort control says what a click will do, and warns where the blanks will land", () => {
  const base = emptyRosterFilters("access");
  expect(sortActionLabel("access", base, "email", "Email")).toBe("Sort by Email, A to Z");
  expect(sortActionLabel("access", base, "attempts", "Requests")).toBe("Sort by Requests, most first");
  expect(sortActionLabel("access", base, "firstSeen", "First signed in")).toBe(
    "Sort by First signed in, newest first — entries with no date sort first"
  );
});

test("the sortable header carries aria-sort, the first one in this frontend", () => {
  expect(SORTABLE).toContain("aria-sort");
  expect(SORTABLE).toContain('"ascending"');
  expect(SORTABLE).toContain('"descending"');
  expect(SORTABLE).toContain('"none"');
  expect(SORTABLE).toContain("ResizableTh");
});

function roundTrip(kind: RosterKind, filters: RosterFilters): RosterFilters {
  const params = new URLSearchParams();
  Object.entries(rosterLinkParams(kind, filters)).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  });
  return rosterFiltersFromSearchParams(kind, params);
}

test("the default state links to a bare path", () => {
  for (const kind of KINDS) {
    const link = rosterLinkParams(kind, emptyRosterFilters(kind));
    expect(Object.values(link).every((value) => value === undefined)).toBe(true);
  }
});

test("a filtered roster survives a paste into another admin's browser", () => {
  const access: RosterFilters = {
    ...emptyRosterFilters("access"),
    search: "ravi",
    status: ["PENDING", "SUSPENDED"],
    roles: ["ADMIN", ADMIT_ROLE_DEFAULT],
    dateField: "requested",
    range: "custom",
    from: "2026-07-01",
    to: "2026-07-31",
    sort: "requested",
    dir: "asc"
  };
  expect(roundTrip("access", access)).toEqual(access);

  const designer: RosterFilters = {
    ...emptyRosterFilters("designer"),
    search: "bagru",
    standing: "suspended",
    roles: ["DESIGNER", ROLE_NEVER_SIGNED_IN],
    institutions: ["NID", INSTITUTION_NONE],
    dateField: "firstSeen",
    range: "90d",
    sort: "institution",
    dir: "asc"
  };
  expect(roundTrip("designer", designer)).toEqual(designer);
});

test("the link carries the preset and never the resolved instants", () => {
  const link = rosterLinkParams("access", { ...emptyRosterFilters("access"), range: "30d" });
  expect(link.range).toBe("30d");
  expect(link.dateFrom).toBeUndefined();
  expect(link.dateTo).toBeUndefined();
});

test("an unknown filter TOKEN is kept for the server to judge", () => {
  const params = new URLSearchParams("roles=ADMIN,ARCHDUKE");
  expect(rosterFiltersFromSearchParams("access", params).roles).toEqual(["ADMIN", "ARCHDUKE"]);
  expect(rosterQueryParams("access", {
    ...emptyRosterFilters("access"),
    roles: ["ADMIN", "ARCHDUKE"]
  }).roles).toContain("ARCHDUKE");
});

test("an unknown control MODE falls back to the default", () => {
  const params = new URLSearchParams("range=fortnight&dateField=hatched&sort=nonsense&dir=sideways&standing=maybe");
  const restored = rosterFiltersFromSearchParams("designer", params);
  expect(restored.range).toBe("any");
  expect(restored.dateField).toBe("added");
  expect(restored.sort).toBe("added");
  expect(restored.dir).toBe("desc");
  expect(restored.standing).toBe("");
});

test("both spellings of a repeated parameter are read", () => {
  const repeated = rosterFiltersFromSearchParams("access", new URLSearchParams("status=PENDING&status=SUSPENDED"));
  const joined = rosterFiltersFromSearchParams("access", new URLSearchParams("status=PENDING,SUSPENDED"));
  expect(repeated.status).toEqual(joined.status);
  expect(joined.status).toEqual(["PENDING", "SUSPENDED"]);
});

test("a sort token from the other roster is not carried across", () => {
  const restored = rosterFiltersFromSearchParams("designer", new URLSearchParams("sort=attempts&dir=desc"));
  expect(restored.sort).toBe("added");
});

test("a sort restored without a direction reads the way its header would have ordered it", () => {
  const restored = rosterFiltersFromSearchParams("access", new URLSearchParams("sort=email"));
  expect(restored.dir).toBe("asc");
});

test("clear every filter clears every filter and keeps the order", () => {
  const filters: RosterFilters = {
    ...emptyRosterFilters("designer"),
    search: "x",
    standing: "active",
    roles: ["DESIGNER"],
    institutions: ["NID"],
    range: "7d",
    sort: "firstSeen",
    dir: "desc"
  };
  const cleared = clearRosterFilters("designer", filters);
  expect(hasActiveRosterFilters("designer", cleared)).toBe(false);
  expect(cleared.sort).toBe("firstSeen");
  expect(cleared.dir).toBe("desc");
});

test("a non-default order alone does not raise the clear-all button", () => {
  const sorted = { ...emptyRosterFilters("access"), sort: "attempts" as const, dir: "desc" as const };
  expect(hasActiveRosterFilters("access", sorted)).toBe(false);
});

test("a custom range with no dates typed still counts as set", () => {
  const custom = { ...emptyRosterFilters("access"), range: "custom" as const };
  expect(hasActiveRosterFilters("access", custom)).toBe(true);
});

test("every control's visible label is its accessible name", () => {
  const blocks = [...BAR.matchAll(/<ControlBlock\b([\s\S]*?)<\/ControlBlock>/g)].map((m) => m[1]);
  expect(blocks.length).toBeGreaterThanOrEqual(5);
  for (const block of blocks) {
    const visible = /\blabel=\{([^}]+)\}/.exec(block);
    expect(visible, block.slice(0, 90)).not.toBeNull();
    const named = `ariaLabel={${visible?.[1]}}`;
    expect(block, `${named} missing`).toContain(named);
  }
  expect(ROSTER_LABELS.clearAll).toBe("Clear every filter");
  expect(ROSTER_LABELS.accessRoles).toBe("Role they join with");
  expect(ROSTER_LABELS.designerRoles).toBe("Role of the linked account");
  expect(ROSTER_LABELS.dateRange).toBe("Date range");
});

test("the roster search boxes have a real accessible name, and so does SearchInput now", () => {
  expect(BAR).toContain("aria-label={ariaLabel}");
  expect(BAR).toContain('role="searchbox"');
  expect(ROSTER_LABELS.accessSearch).toContain("note");
  expect(ROSTER_LABELS.designerSearch).toContain("institution");
});

test("the date range is a real fieldset with sibling labels, never a wrapping one", () => {
  expect(BAR).toContain("<fieldset");
  expect(BAR).toContain("<legend");
  expect(BAR).toContain("id={fromId}");
  expect(BAR).toContain("id={toId}");
  expect(BAR).toContain("<CalendarField");
  expect(BAR).not.toMatch(/<label[^>]*>\s*\{?ROSTER_LABELS\.dateFrom[\s\S]{0,40}<(DateField|CalendarField)/);
  const CALENDAR = readFileSync(join(__dirname, "..", "components", "ui", "CalendarField.tsx"), "utf8");
  expect(CALENDAR).toContain("<label htmlFor={fieldId}");
});

test("the access filter row says why it has no institution filter", () => {
  expect(BAR).toContain("ACCESS_INSTITUTION_NOTE");
  expect(rosterQueryParams("access", {
    ...emptyRosterFilters("access"),
    institutions: ["NID"]
  }).institutions).toBeUndefined();
});

test("both roster pages mount the shared bar and hand it one handler", () => {
  for (const page of PAGES) {
    expect(page.source, page.name).toContain("<RosterFilterBar");
    expect(page.source, page.name).toContain("<SortableTh");
    expect(page.source, page.name).toContain("onChange={applyFilters}");
    expect(page.source, page.name).toContain("onChange: applyFilters");
  }
});

test("the default state of either page asks the server the question it asked before req 30", () => {
  const ABSENT = [
    "roles",
    "status",
    "standing",
    "institutions",
    "dateField",
    "dateFrom",
    "dateTo",
    "sort",
    "dir",
    "activeOnly"
  ];
  for (const kind of KINDS) {
    const query = buildQuery({
      page: 1,
      pageSize: 20,
      ...rosterQueryParams(kind, emptyRosterFilters(kind))
    });
    expect(query, kind).toBe("?page=1&pageSize=20");
    for (const key of ABSENT) expect(query, kind + ": " + key).not.toContain(key);
  }
});

test("every filter multi-select under app/(protected)/admin passes bulk={false}", () => {
  const files = [
    ...sourcesUnder(...ADMIN_DIR),
    { name: "components/admin/RosterFilterBar.tsx", source: BAR }
  ];
  expect(files.length).toBeGreaterThanOrEqual(3);
  let seen = 0;
  for (const file of files) {
    for (const control of multiSelects(file.source)) {
      seen += 1;
      expect(control, file.name + ": " + control.slice(0, 80)).toContain("bulk={false}");
      expect(control, file.name + ": " + control.slice(0, 80)).toContain("confirmOnSelect={false}");
    }
  }
  expect(seen).toBeGreaterThanOrEqual(3);
});

test("neither roster page seeds a narrowing filter state", () => {
  for (const page of PAGES) {
    expect(page.source, page.name).toContain("rosterFiltersFromSearchParams");
    expect(page.source, page.name).toContain("useState<RosterFilters>(() =>");
    expect(page.source, page.name).not.toMatch(/useState<RosterFilters>\(\s*\{/);
  }
});

test("neither roster page sends activeOnly beside standing", () => {
  for (const page of PAGES) {
    expect(withoutComments(page.source), page.name).not.toContain("activeOnly");
  }
  expect(withoutComments(MODULE)).not.toContain("activeOnly:");
});

test("neither roster page filters or sorts a fetched page", () => {
  for (const page of PAGES) {
    const source = withoutComments(page.source);
    expect(source, page.name + ": .filter() over a fetched page").not.toMatch(/\.\s*filter\s*\(/);
    expect(source, page.name + ": .sort() over a fetched page").not.toMatch(/\.\s*sort\s*\(/);
    expect(source, page.name).toMatch(/rosterQueryParams|listFilteredAccessRoster/);
  }
});

test("every filter and sort change resets the pager on both pages", () => {
  for (const page of PAGES) {
    const handler = /applyFilters\s*=\s*useCallback\(\s*\([^)]*\)\s*=>\s*\{([\s\S]*?)\}\s*,\s*\[\]\s*\)/.exec(
      page.source
    );
    expect(handler, page.name + ": applyFilters is not a stable useCallback").not.toBeNull();
    expect(handler?.[1], page.name).toContain("setPage(1)");
  }
});

test("every sortable header names a column its own roster actually offers", () => {
  for (const page of PAGES) {
    const columns = [...page.source.matchAll(/<SortableTh\b[^>]*?column="([A-Za-z]+)"/g)].map(
      (match) => match[1]
    );
    expect(columns.length, page.name + ": no sortable headers at all").toBeGreaterThanOrEqual(4);
    for (const column of columns) {
      expect(rosterSortSpec(page.kind, column), page.name + ': column="' + column + '"').toBeDefined();
    }
    expect(columns, page.name).toContain("added");
  }
});

test("both pages state the cuts the server reports, inside a region that was already there", () => {
  expect(DESIGNER_PAGE).toContain("roleMatchTruncated={data?.roleMatchTruncated}");
  expect(ACCESS_PAGE).toContain("accessRoleCutNotice(data?.roleMatchTruncated)");
  for (const page of PAGES) expect(page.source, page.name).toContain("CUT_NOTICE_LIVE_REGION");
});

test("the directory cap notice survives this work", () => {
  expect(DESIGNER_PAGE).toContain("DIRECTORY_CAP");
  expect(DESIGNER_PAGE).toContain("directoryCapped");
});
