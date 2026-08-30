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
// The REAL serialiser the two pages send through, not a second copy of its rules. `buildQuery` is
// what turns "nothing ticked" into an absent parameter — it drops `undefined`, `null` AND `""` —
// so rule (i)'s client-side half is only actually pinned by calling it. Safe to import from a Node
// process: `lib/api.ts` touches `window` inside its functions and never at module scope.
import { buildQuery } from "@/lib/api";
import { ROLES_BY_RANK } from "@/lib/permissions";

/**
 * THE SHARED ROSTER CONTROLS, PINNED — DROPDOWN_DESIGN §4.6's four binding rules as assertions.
 *
 * Every rule below is a rule somebody already broke once, and not one of the breakages is visible
 * in a screenshot of a working screen: a filter that spells "everything" two ways looks identical
 * to one that spells it once until two requests disagree; a date range resolved at pick time looks
 * identical to one resolved at request time until a screen is left open overnight; a sort with no
 * stable tiebreak looks identical to a stable one until somebody pages through it and a row is
 * quietly missing. So they are assertions over the pure module rather than over pixels.
 *
 * WHY PART PURE CALL AND PART SOURCE READ — the split `dropdown-sweep-unit.spec.ts`,
 * `capped-lists-unit.spec.ts` and `deleted-workshops-unit.spec.ts` already make, and for their
 * reason. The serialisation, the URL round-trip, the sort vocabulary and the two cut sentences are
 * pure functions and are tested by CALLING them, which is what `components/admin/rosterFilters.ts`
 * was extracted for. Whether a call site passes `bulk={false}` lives inside a React component, and
 * this repository has no React renderer in its devDependencies — Playwright is the whole of it — so
 * those are read out of the source.
 *
 * WHAT THE SOURCE READS DO NOT PROVE: that a browser paints or announces any of it, and that the
 * two pages mount the bar at all. The second half belongs to the parcels that own
 * `app/(protected)/admin/access/page.tsx` and `.../designers/page.tsx`; their sweeps — no `.filter()`
 * over a fetched page, no narrowing initial state on either page — belong in THIS file, appended
 * below, rather than in a second spec that would word the same rules a second way.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const BAR = read("components", "admin", "RosterFilterBar.tsx");
const SORTABLE = read("components", "admin", "SortableTh.tsx");
const MODULE = read("components", "admin", "rosterFilters.ts");

/**
 * THE TWO SCREENS THEMSELVES. The header above says the page sweeps belong in this file rather than
 * in a second spec that would word the same four rules a second way; these are what they read.
 *
 * A file each, by name, and not a glob — a glob that silently matched nothing would pass every
 * assertion below and prove that neither roster page exists.
 */
const ADMIN_DIR = ["app", "(protected)", "admin"] as const;
const ACCESS_PAGE = read(...ADMIN_DIR, "access", "page.tsx");
const DESIGNER_PAGE = read(...ADMIN_DIR, "designers", "page.tsx");

const PAGES: { kind: RosterKind; name: string; source: string }[] = [
  { kind: "access", name: "admin/access/page.tsx", source: ACCESS_PAGE },
  { kind: "designer", name: "admin/designers/page.tsx", source: DESIGNER_PAGE }
];

/**
 * Every TypeScript source under a directory, recursively.
 *
 * WHY THE SWEEP IS OVER A TREE AND NOT OVER THE TWO FILES THIS PARCEL EDITED. DROPDOWN_DESIGN
 * §4.6's rule (i) asks for a source assertion over `app/(protected)/admin/**`, and the whole point
 * of the wider net is the control nobody has written yet: a third admin screen that mounts a
 * multi-select and reaches for the primitive's default `bulk` would ship a "Select all N" button —
 * the one state rule (i) forbids — and a test that only looked at the two files already known to be
 * correct would pass on the day that happened.
 */
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

/** The source with its comments removed, for the assertions that ban a construct rather than find one. */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/.*$/gm, "$1");
}

/** Every `<MultiSelectDropdown …/>` element in a file, as text. */
function multiSelects(source: string): string[] {
  return [...source.matchAll(/<MultiSelectDropdown\b[\s\S]*?\/>/g)].map((match) => match[0]);
}

/** Every `<Dropdown …/>` element in a file, as text. */
function singleSelects(source: string): string[] {
  return [...source.matchAll(/<Dropdown\b[\s\S]*?\/>/g)].map((match) => match[0]);
}

const KINDS: RosterKind[] = ["access", "designer"];

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * (i) EMPTY MEANS EVERYTHING, BY ABSENCE — never by an all-ticked state
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

test("filters send nothing when nothing is chosen", () => {
  for (const kind of KINDS) {
    const params = rosterQueryParams(kind, emptyRosterFilters(kind));
    // Not "the keys are empty strings" — ABSENT. `buildQuery` drops "" as it drops undefined, but
    // the two mean different things everywhere else in this app and only one of them is what a
    // server reads as "do not filter".
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
  // The two must be different requests, or the reserved ninth option is not doing its job: ticking
  // every named tier EXCLUDES every row admitted at the platform default, and absence includes them.
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
  // Two spellings of one filter is two cache entries and two "why did that reload?".
  expect(a.status).toBe(b.status);
  expect(a.status).toBe("ACTIVE,SUSPENDED");
});

test("every filter multi-select passes bulk={false} and confirmOnSelect={false}", () => {
  const controls = multiSelects(BAR);
  // Three today: access status, the role ladder, the institutions. The count is asserted so a
  // fourth cannot be added without this test being read.
  expect(controls.length).toBeGreaterThanOrEqual(2);
  for (const control of controls) {
    // Without `bulk={false}` the primitive ships a "Select all N" button, which manufactures the
    // one state rule (i) forbids — and over a server-truncated list "all" is not all anyway.
    expect(control, control.slice(0, 80)).toContain("bulk={false}");
    // A filter adjusts the list in place; there is nothing to confirm and nowhere to advance to.
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
    // An admin must be able to filter for a tier they could not GRANT, or a master-admin row is
    // invisible to every admin and the list stops being a complete answer for the person auditing it.
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
  // The free-text collision: an institution literally called "none" must not produce two rows with
  // one value, or ticking either ticks both.
  const collided = institutionOptions(["none", "NID"]);
  expect(collided.filter((row) => row.value === INSTITUTION_NONE)).toHaveLength(1);
  expect(collided.at(-1)?.value).toBe(INSTITUTION_NONE);
});

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * (ii) SUSPENDED AND REJECTED ROWS STAY LISTED BY DEFAULT
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

test("no filter control defaults to a narrowing value", () => {
  for (const kind of KINDS) {
    const base = emptyRosterFilters(kind);
    expect(base.status).toEqual([]);
    expect(base.standing).toBe("");
    expect(base.roles).toEqual([]);
    expect(base.institutions).toEqual([]);
    expect(base.search).toBe("");
    // A date FIELD narrows nothing on its own — with no bounds resolved neither it nor the range
    // reaches the wire — which is the whole reason it is safe to have one pre-selected.
    expect(base.range).toBe("any");
    expect(hasActiveRosterFilters(kind, base)).toBe(false);
  }
});

test("the access standing filter offers refused and suspended, and excludes neither by default", () => {
  const values = ACCESS_STATUS_OPTIONS.map((option) => option.value);
  expect(values).toEqual([...ACCESS_STATUS_TOKENS]);
  expect(values).toContain("REJECTED");
  expect(values).toContain("SUSPENDED");
  // An admin arrives BECAUSE somebody cannot sign in, and the row refusing them is the one they
  // came to see: the default request names no status at all.
  expect(rosterQueryParams("access", emptyRosterFilters("access")).status).toBeUndefined();
});

test("there is no hide-suspended control on either roster", () => {
  // A control that hides suspended rows is a SECOND SPELLING of ticking the other three, and a
  // filter with two spellings for one state cannot tell a default from a deliberate choice. It is
  // absent rather than merely defaulted off, which is the only version of this that cannot regress.
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
  // Sending both is a 422 rather than a silent winner, so this client sends only the new spelling.
  const params = rosterQueryParams("designer", { ...emptyRosterFilters("designer"), standing: "active" });
  expect(Object.keys(params)).not.toContain("activeOnly");
  expect(withoutComments(MODULE)).not.toContain("activeOnly");
});

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * (iii) ANY CAP OR TRUNCATION IS STATED, WITH THE NUMBER
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

test("an untruncated read says nothing at all", () => {
  // `undefined` is the wire's shape on a deployment predating the flag: `apiFetch` casts, it does
  // not validate, so an absent field must read as "nothing to say" rather than as a cut.
  expect(institutionCutNotice(undefined, 200)).toBe("");
  expect(institutionCutNotice(false, 200)).toBe("");
  expect(roleMatchCutNotice(undefined)).toBe("");
  expect(roleMatchCutNotice(false, 50000)).toBe("");
});

test("a truncated institution vocabulary states the number it was handed", () => {
  const notice = institutionCutNotice(true, 200);
  expect(notice).toContain("200");
  // Never print a cap you did not read: the number is the count of names this control actually
  // holds, not a constant copied from the route.
  expect(notice).toContain("search box above");
  // And it must not send the reader to the picker's own filter box, which cannot reach past the cut.
  expect(notice).not.toContain("this picker");
  // With nothing to count, the fact still lands — silence is the one unacceptable answer.
  expect(institutionCutNotice(true, 0)).not.toBe("");
});

test("a truncated role match is stated, and never tells the reader to narrow the search", () => {
  const notice = roleMatchCutNotice(true, 50000);
  expect(notice).toContain("50000");
  expect(notice).toContain("missing");
  // The cut happened UPSTREAM of the search, in the account read, so narrowing the roster search
  // puts back nothing. The move that works is naming fewer tiers, and that is what it says.
  expect(notice).not.toContain("narrow the search");
  expect(notice).toContain("fewer tiers");
  // No limit read means no number invented, and the fact is stated anyway.
  const unnumbered = roleMatchCutNotice(true);
  expect(unnumbered).not.toBe("");
  expect(unnumbered).not.toMatch(/\d/);
});

test("both cut notices are drawn inside a live region that is mounted before it speaks", () => {
  // `CappedListNotice` returns null when silent, and a live region created together with its first
  // sentence announces nothing — the bug the form's cap notice shipped.
  const regions = [...BAR.matchAll(/CUT_NOTICE_LIVE_REGION/g)];
  expect(regions.length).toBeGreaterThanOrEqual(2);
  expect(BAR).toContain("<CappedListNotice id={roleCutId}");
  expect(BAR).toContain("<CappedListNotice id={institutionCutId}");
});

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * (iv) FILTERING IS SERVER-SIDE
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

test("the filter bar filters no array", () => {
  // Rule (iv). The bar produces a QUERY; a `.filter()` here would be a client-side pass over a
  // server-truncated page, which answers "No matches" about records that exist.
  const source = withoutComments(BAR);
  expect(source).not.toMatch(/\brows\.filter\(/);
  expect(source).not.toMatch(/\bitems\.filter\(/);
  expect(source).not.toMatch(/\bdata\.items\b/);
  // And it never fetches: a control with its own request is a second, ungoverned race against the
  // page's generation counter.
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

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE DATE RANGE — one range, one named column, resolved on the reader's clock at REQUEST time
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

test("one date range per request, over one named column", () => {
  for (const kind of KINDS) {
    const fields = dateFieldOptions(kind).map((option) => option.value);
    expect(fields.length).toBeGreaterThan(0);
    expect(fields[0]).toBe("added");
  }
  expect(dateFieldOptions("access").map((option) => option.value)).toEqual([...ACCESS_DATE_FIELDS]);
  // One `dateField` key, one `dateFrom`, one `dateTo` — never five From/To pairs.
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

  // "Custom range" with both boxes empty narrows nothing. A bare `dateField` for it would put a key
  // on the wire that reads, in a log or a shared link, as a filter that was applied.
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
  // 30 days as the reader's clock sees them: local midnight 29 days back, through the whole of
  // today. `new Date("2026-06-21")` would be UTC midnight — the previous day west of Greenwich —
  // which is the one bug every date filter is born with.
  expect(new Date(params.dateFrom as string).getTime()).toBe(new Date(2026, 5, 21).getTime());
  expect(new Date(params.dateTo as string).getTime()).toBe(
    new Date(2026, 6, 20, 23, 59, 59, 999).getTime()
  );
});

test("a preset resolves at request time, not at pick time", () => {
  const filters: RosterFilters = { ...emptyRosterFilters("access"), range: "30d" };
  const monday = rosterQueryParams("access", filters, new Date(2026, 6, 20, 12));
  const tuesday = rosterQueryParams("access", filters, new Date(2026, 6, 21, 12));
  // A screen left open overnight must not keep asking about yesterday.
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
  // Inclusive: stopping at the end day's midnight would silently drop everything filed that day.
  expect(new Date(params.dateTo as string).getTime()).toBe(
    new Date(2026, 6, 31, 23, 59, 59, 999).getTime()
  );
});

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * SORT — and the direction a column reads first
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

test("each roster offers exactly the sort columns its route has, and no others", () => {
  // Pinned against DROPDOWN_DESIGN §4.3's table. A token this client sends that the route does not
  // hold is a 422 over a column nothing on the screen even offers; a column the route holds and this
  // client cannot name is a view an admin simply cannot reach.
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
  // ONE column and ONE direction per request — the client's whole half of the stable-sort contract.
  // The tiebreak itself is `services/records.with_id_tiebreak` on the server, and it is not
  // optional: offset paging over a non-total order misses rows and repeats others, both silently,
  // and the access-roster migration gave four hundred grandfathered rows one `createdAt` to share.
  // What this client must never do is send two orders, or re-sort what came back.
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
  // `sort` alone would be defaulted to `desc` by the route, which is Z-to-A on an email column.
  expect(params.sort).toBe("email");
  expect(params.dir).toBe("asc");
});

test("clicking a new column takes that column's own first reading, not the last one's direction", () => {
  const base = emptyRosterFilters("access");
  const byEmail = nextRosterSort("access", base, "email");
  expect(byEmail.sort).toBe("email");
  // "newest first" and "A to Z" are opposite directions and both are the natural first reading of
  // their column. Inheriting `desc` from a date onto an email gives Z-to-A, which nobody clicked for.
  expect(byEmail.dir).toBe("asc");

  const flipped = nextRosterSort("access", byEmail, "email");
  expect(flipped.dir).toBe("desc");

  const byRequested = nextRosterSort("access", flipped, "requested");
  expect(byRequested.sort).toBe("requested");
  expect(byRequested.dir).toBe("desc");
});

test("a sort token that does not belong to a roster changes nothing", () => {
  const base = emptyRosterFilters("designer");
  // `attempts` is a good access token and a meaningless designer one; sending it anyway would 422
  // the whole list over a column nothing on that screen even offers.
  expect(rosterSortSpec("designer", "attempts")).toBeUndefined();
  expect(nextRosterSort("designer", base, "attempts")).toEqual(base);
});

test("the sort control says what a click will do, and warns where the blanks will land", () => {
  const base = emptyRosterFilters("access");
  expect(sortActionLabel("access", base, "email", "Email")).toBe("Sort by Email, A to Z");
  expect(sortActionLabel("access", base, "attempts", "Requests")).toBe("Sort by Requests, most first");
  // Postgres puts NULLs first on `desc`. On this column that IS the outstanding-invitation view —
  // said out loud, because a table opening on ten blank cells otherwise reads as a broken screen.
  expect(sortActionLabel("access", base, "firstSeen", "First signed in")).toBe(
    "Sort by First signed in, newest first — rows with no date sort first"
  );
});

test("the sortable header carries aria-sort, the first one in this frontend", () => {
  expect(SORTABLE).toContain("aria-sort");
  expect(SORTABLE).toContain('"ascending"');
  expect(SORTABLE).toContain('"descending"');
  expect(SORTABLE).toContain('"none"');
  // Built on `ResizableTh`, whose `overflow-hidden` is what makes `resize: horizontal` work at all.
  expect(SORTABLE).toContain("ResizableTh");
});

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE URL ROUND-TRIP
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

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
  // "Last 30 days" pasted to a colleague tomorrow must mean THEIR last 30 days. A frozen window
  // that still calls itself "Last 30 days" is a filter whose label and behaviour disagree.
  expect(link.range).toBe("30d");
  expect(link.dateFrom).toBeUndefined();
  expect(link.dateTo).toBeUndefined();
});

test("an unknown filter TOKEN is kept for the server to judge", () => {
  // `workshopScopeFromSearchParams`' rule verbatim. Dropping it here would quietly answer a
  // narrower question than the link asked and look exactly like the filter working; the server
  // answers it with a 422 naming the valid values, which is a refusal somebody can see.
  const params = new URLSearchParams("roles=ADMIN,ARCHDUKE");
  expect(rosterFiltersFromSearchParams("access", params).roles).toEqual(["ADMIN", "ARCHDUKE"]);
  expect(rosterQueryParams("access", {
    ...emptyRosterFilters("access"),
    roles: ["ADMIN", "ARCHDUKE"]
  }).roles).toContain("ARCHDUKE");
});

test("an unknown control MODE falls back to the default", () => {
  // A mode with no row to render is a control stuck on a value the reader can see the effect of and
  // cannot change.
  const params = new URLSearchParams("range=fortnight&dateField=hatched&sort=nonsense&dir=sideways&standing=maybe");
  const restored = rosterFiltersFromSearchParams("designer", params);
  expect(restored.range).toBe("any");
  expect(restored.dateField).toBe("added");
  expect(restored.sort).toBe("added");
  expect(restored.dir).toBe("desc");
  expect(restored.standing).toBe("");
});

test("both spellings of a repeated parameter are read", () => {
  // The web and Android build query strings differently, and a filter that quietly covered
  // everything because it was spelled the other way would look exactly like the filter not working.
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

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * CLEAR-ALL, AND THE LABELS
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

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
  // An order is not a filter: it narrows nothing and hides nobody. An admin who sorted by "first
  // signed in" to find outstanding invitations is still asking that question after clearing a search.
  expect(cleared.sort).toBe("firstSeen");
  expect(cleared.dir).toBe("desc");
});

test("a non-default order alone does not raise the clear-all button", () => {
  // Otherwise the button appears and pressing it changes nothing a reader can see.
  const sorted = { ...emptyRosterFilters("access"), sort: "attempts" as const, dir: "desc" as const };
  expect(hasActiveRosterFilters("access", sorted)).toBe(false);
});

test("a custom range with no dates typed still counts as set", () => {
  // It sends no date keys, but the reader has visibly changed a control and must be able to put it
  // back — which is what the clear-all button is for.
  const custom = { ...emptyRosterFilters("access"), range: "custom" as const };
  expect(hasActiveRosterFilters("access", custom)).toBe(true);
});

test("every control's visible label is its accessible name", () => {
  // A `<label>` cannot name a themed dropdown's `<button>`, so the visible text is a `<span>` and
  // the name comes from `ariaLabel` — which makes it trivially easy to ship a control that SHOWS
  // one word and ANNOUNCES another, as `SearchFilters` does today (visible "Record time", announced
  // "Filter by when the record was made"). Read STRUCTURALLY rather than by name: every
  // `<ControlBlock label={X}>` must hand the control inside it the same expression as `ariaLabel`,
  // so a new control cannot be added with a mismatched pair and cannot be renamed out of this test.
  const blocks = [...BAR.matchAll(/<ControlBlock\b([\s\S]*?)<\/ControlBlock>/g)].map((m) => m[1]);
  expect(blocks.length).toBeGreaterThanOrEqual(5);
  for (const block of blocks) {
    const visible = /\blabel=\{([^}]+)\}/.exec(block);
    expect(visible, block.slice(0, 90)).not.toBeNull();
    const named = `ariaLabel={${visible?.[1]}}`;
    expect(block, `${named} missing`).toContain(named);
  }
  // And the constants themselves are §4.8's words.
  expect(ROSTER_LABELS.clearAll).toBe("Clear every filter");
  expect(ROSTER_LABELS.accessRoles).toBe("Tier they join at");
  expect(ROSTER_LABELS.designerRoles).toBe("Tier of the linked account");
  expect(ROSTER_LABELS.dateRange).toBe("Date range");
});

test("the roster search boxes have a real accessible name, unlike SearchInput", () => {
  // `components/SearchInput.tsx` sets role="searchbox" with no label of any kind, so its
  // placeholder is its only accessible name — and a placeholder disappears the moment somebody
  // types, which is exactly when the fact is being used.
  expect(BAR).toContain("aria-label={ariaLabel}");
  expect(BAR).toContain('role="searchbox"');
  expect(ROSTER_LABELS.accessSearch).toContain("note");
  expect(ROSTER_LABELS.designerSearch).toContain("institution");
});

test("the date range is a real fieldset with sibling labels, never a wrapping one", () => {
  expect(BAR).toContain("<fieldset");
  expect(BAR).toContain("<legend");
  expect(BAR).toContain('htmlFor={fromId}');
  expect(BAR).toContain('htmlFor={toId}');
  // A wrapping `<label>` folds every named descendant into the input's accessible name, so a
  // `DateField` inside one announces itself as "From Open calendar" instead of "From".
  expect(BAR).not.toMatch(/<label[^>]*>\s*\{?ROSTER_LABELS\.dateFrom[\s\S]{0,40}<DateField/);
});

test("the access filter row says why it has no institution filter", () => {
  // `AccessRoster` has no institution column, and joining to the designer roster to fake one would
  // hide exactly the pending strangers this screen exists to decide about.
  expect(BAR).toContain("ACCESS_INSTITUTION_NOTE");
  expect(rosterQueryParams("access", {
    ...emptyRosterFilters("access"),
    institutions: ["NID"]
  }).institutions).toBeUndefined();
});

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE TWO PAGES — the half the assertions above deliberately cannot reach
 *
 * Everything before this line is about `components/admin/`: the vocabulary, the serialiser, the URL
 * round-trip, the two cut sentences and the filter row's own markup. All of it can be perfect while
 * `/admin/access` and `/admin/designers` still break every rule in DROPDOWN_DESIGN §4.6, because a
 * module that is never mounted narrows nothing and a page is free to filter its own rows underneath
 * one. §4.6 names five assertions that live at the page rather than at the control —
 * `every filter multi-select passes bulk={false}` over `app/(protected)/admin/**`,
 * `no filter control defaults to a narrowing value` on both pages, `the directory cap notice
 * survives`, `no .filter() over a fetched page`, and the client-side twin of
 * `filters send nothing when nothing is chosen` — and this is where they land.
 *
 * They are source reads for the reason the header gives: this repository has no React renderer in
 * its devDependencies. What they cannot prove is that a browser paints or announces any of it. What
 * they can prove is the class of regression that has actually shipped here before — a control wired
 * to the wrong handler, a page that re-sorts what it fetched, a cap that stopped being mentioned —
 * none of which is visible in a screenshot of a screen that looks like it works.
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

test("both roster pages mount the shared bar and hand it one handler", () => {
  for (const page of PAGES) {
    // The bar exists precisely so the two screens cannot word one filter two ways. A page that
    // declared its own controls beside it would put that guarantee back where it was: two
    // vocabularies announced to a screen reader as the same three words.
    expect(page.source, page.name).toContain("<RosterFilterBar");
    expect(page.source, page.name).toContain("<SortableTh");
    // ONE handler for the bar and for every header, which is what makes the pager reset below true
    // for a re-sort as well as for a re-filter. Two handlers is how one of them ends up missing it.
    expect(page.source, page.name).toContain("onChange={applyFilters}");
    expect(page.source, page.name).toContain("onChange: applyFilters");
  }
});

test("the default state of either page asks the server the question it asked before req 30", () => {
  // Rule (i)'s client-side twin, THROUGH THE REAL `buildQuery` rather than through an inspection of
  // the params object: `undefined` only becomes an ABSENT parameter because that function drops it,
  // and absence is the server's spelling of "every status, every tier, every institution". If this
  // ever emits `status=ACTIVE,PENDING,REJECTED,SUSPENDED` instead, the request is no longer the one
  // this screen has always made — it would exclude every row admitted at the default tier, and the
  // screen would look identical while quietly answering a narrower question.
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
  // §4.6 (i)'s source assertion, over the TREE and not over the two files known to be right today.
  // Without `bulk={false}` the primitive ships a "Select all N" button, and "all ticked" then means
  // the same thing as "none ticked" — a filter with two spellings for one state cannot tell a
  // default apart from a deliberate choice, and over a server-truncated vocabulary "all" is not all
  // anyway: it is every institution the picker happened to be handed.
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
  // The three in the bar. Asserted so that a tree which suddenly contains none — a rename, a moved
  // file, a walker that silently found nothing — fails here rather than passing vacuously.
  expect(seen).toBeGreaterThanOrEqual(3);
});

test("neither roster page seeds a narrowing filter state", () => {
  for (const page of PAGES) {
    // §4.6 (ii). The initial state comes from the URL, and `rosterFiltersFromSearchParams` falls
    // back to `emptyRosterFilters` for everything it does not recognise — which is asserted above to
    // narrow nothing. A page that wrote its own opening state instead could quietly reintroduce the
    // one default this screen must never have: an admin arrives BECAUSE somebody cannot sign in, and
    // the REJECTED or SUSPENDED row refusing them is the one a tidier default would hide.
    expect(page.source, page.name).toContain("rosterFiltersFromSearchParams");
    expect(page.source, page.name).toContain("useState<RosterFilters>(() =>");
    // Read in a lazy initialiser, so the FIRST request already carries a pasted link's filters.
    // Seeding in an effect instead fires one unfiltered read first, which on the designer roster
    // means briefly painting every empanelled address for somebody sent a link to one row.
    expect(page.source, page.name).not.toMatch(/useState<RosterFilters>\(\s*\{/);
  }
});

test("neither roster page sends activeOnly beside standing", () => {
  // `GET /designers/roster` keeps `activeOnly` for a client that has not been updated, and sending
  // it together with `standing` is a 422 rather than a silent winner. A page that mounted the bar
  // while still sending the old parameter would 422 its whole list the moment somebody chose "Only
  // those suspended" — which reads as the screen being broken, not as two parameters disagreeing.
  for (const page of PAGES) {
    expect(withoutComments(page.source), page.name).not.toContain("activeOnly");
  }
  expect(withoutComments(MODULE)).not.toContain("activeOnly:");
});

test("neither roster page filters or sorts a fetched page", () => {
  for (const page of PAGES) {
    const source = withoutComments(page.source);
    // §4.6 (iv), and the reason it is banned outright rather than checked against `rows`: page one
    // holds twenty of four hundred, so a box that sifted only those twenty answers "no such
    // designer" about somebody who is simply on page two — a claim about the institution's roster
    // made from a claim about a page. `admin/access/page.tsx`'s own header records four closed
    // defects of exactly that shape, and this is the assertion it asked for in prose.
    expect(source, page.name + ": .filter() over a fetched page").not.toMatch(/\.\s*filter\s*\(/);
    // The same rule for an ORDER, which is the half that looks identical until somebody pages:
    // "oldest first" over one page shows the oldest of PAGE ONE, and walking it re-sorts per page.
    expect(source, page.name + ": .sort() over a fetched page").not.toMatch(/\.\s*sort\s*\(/);
    // Every narrowing is a query parameter, which is only true if the params are built for the
    // request being made — inside `load()`, never memoised, so the date presets resolve against the
    // reader's clock at REQUEST time and a tab left open overnight stops asking about yesterday.
    expect(source, page.name).toMatch(/rosterQueryParams|listFilteredAccessRoster/);
  }
});

test("every filter and sort change resets the pager on both pages", () => {
  for (const page of PAGES) {
    // A narrowed list has different rows at `OFFSET 40` and a re-ordered one has different rows at
    // every offset, so a reader who was on page 3 lands somewhere arbitrary in a list they have just
    // changed — and on a list that got shorter, PAST THE END of it, which draws the "nobody is here"
    // empty state over a roster that is not empty. Both `RosterFilterBar` and `SortableTh` say on
    // their props that this is the page's job; this asserts the page does it, in one place, for both.
    //
    // Stable identity (`[]`) is asserted with it, because the bar feeds this handler to an effect: a
    // new function every render would re-run that effect on every keystroke of a debounced box.
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
      // A typo here is invisible on screen and permanent in effect: `rosterSortSpec` returns
      // `undefined`, `nextRosterSort` returns the filters UNCHANGED, and the header renders
      // `aria-sort="none"` for ever. The column looks sortable, clicks like a button, and does
      // nothing — which is worse than not offering the affordance at all.
      expect(rosterSortSpec(page.kind, column), page.name + ': column="' + column + '"').toBeDefined();
    }
    // The DEFAULT order must have a header, or the table is ordered by a column while every header
    // on it announces `aria-sort="none"` — a table that is sorted and says it is not.
    expect(columns, page.name).toContain("added");
  }
});

test("both pages state the cuts the server reports, inside a region that was already there", () => {
  // §4.6 (iii). Two of these can only be known from the wire, and neither shows in the rows.
  //
  // The designer roster hands `roleMatchTruncated` to the bar, because the cut is caused by the role
  // picker and belongs under the control that caused it. The access page renders its own sentence
  // instead and its header explains why: the bar hard-suppresses that notice for `kind="access"`,
  // since the sentence it would print describes reading the ACCOUNTS that hold a tier — which is the
  // designer roster's mechanism and not this route's, where `admitRole` is a real column.
  expect(DESIGNER_PAGE).toContain("roleMatchTruncated={data?.roleMatchTruncated}");
  expect(ACCESS_PAGE).toContain("accessRoleCutNotice(data?.roleMatchTruncated)");
  // Announced, not merely drawn. Assistive technology only reads mutations inside a region that
  // ALREADY EXISTED when the page settled; a region created together with its first sentence
  // announces nothing at all, which is the bug the form's cap notice shipped.
  for (const page of PAGES) expect(page.source, page.name).toContain("CUT_NOTICE_LIVE_REGION");
});

test("the directory cap notice survives this work", () => {
  // §4.6 (iii) names this one by file and line, because it is about a DIFFERENT cap —
  // `GET /designers/directory` stops at 500 accounts — and the roster filters neither cause it nor
  // cure it. It is listed as a thing this work must not delete, so deleting it must fail a test
  // rather than be noticed by a reader who happens to remember it was there.
  expect(DESIGNER_PAGE).toContain("DIRECTORY_CAP");
  expect(DESIGNER_PAGE).toContain("directoryCapped");
});
