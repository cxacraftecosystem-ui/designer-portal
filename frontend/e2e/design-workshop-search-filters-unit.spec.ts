import { expect, test } from "@playwright/test";

import {
  DESIGN_WORKSHOP_TYPE,
  EMPTY_SEARCH_FILTERS,
  RECORD_TYPES,
  SEARCH_MEDIA_TYPES,
  SEARCH_RECORD_TYPES,
  activeFilterCount,
  filtersFromSearchParams,
  filtersToLinkParams,
  hasActiveFilters,
  searchFilterParams,
  typeVisible
} from "@/components/search/SearchFilters";
import {
  DESIGN_WORKSHOP_DATA_EXPORT_ROLES,
  DESIGN_WORKSHOP_DATA_VIEW_ROLES,
  DESIGN_WORKSHOP_ROLES,
  canExportDesignWorkshopData,
  canRunDesignWorkshops,
  canViewDesignWorkshopData
} from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * The search vocabulary and the two design-workshop data predicates, as pure functions.
 *
 * WHY THIS SPEC EXISTS. Three of the things it pins are invisible to `tsc` and would ship green:
 *
 *   1. **The map must keep FIVE buckets.** `GET /map/points` groups by `locationId`/`place`, which
 *      `DesignWorkshop` does not have, so a sixth bucket reaching that endpoint is a 500 rather than
 *      an empty result. `RECORD_TYPES` and `SEARCH_RECORD_TYPES` are both `readonly string[]` to the
 *      compiler, so nothing but an assertion keeps them apart.
 *   2. **`filtersFromSearchParams` must respect the vocabulary it is handed.** It reads `?type=`
 *      out of a URL for every consumer, and a link written by /search pasted into /map would
 *      otherwise put the map into a state its own chips cannot express or undo.
 *   3. **The two permission sets must stay different sizes.** A professor reads design-workshop
 *      tables on screen and may not export them; collapsing the two into one predicate would either
 *      hand them a file of stage answers or take the seven legacy tables away from them, and both
 *      type-check perfectly. `backend/tests/test_design_workshop_data_access.py` holds the same
 *      pair against `deps.py` and reads THIS FILE's source for the sets.
 *
 * A NODE SPEC, no browser: everything here is a pure function over plain values.
 */

function asUser(role: UserRole): User {
  // Only `role` is read by these predicates; the rest of `User` is filled in so the object is a real
  // one rather than a cast that would let a predicate start reading a field nobody supplied.
  return {
    id: "u1",
    name: "Test",
    email: "test@example.com",
    role,
    createdAt: new Date().toISOString()
  } as User;
}

const EVERY_ROLE: UserRole[] = [
  "CROWDSOURCE_VOLUNTEER",
  "FIELD_CONTRIBUTOR",
  "RESEARCHER",
  "DESIGNER",
  "INSPECTOR",
  "PROFESSOR",
  "ADMIN",
  "MASTER_ADMIN"
];

/* ── the vocabularies ─────────────────────────────────────────────────────── */

test("the map keeps five buckets and search gets six", () => {
  expect(RECORD_TYPES).toEqual(["artisans", "workshops", "products", "tools", "media"]);
  expect(RECORD_TYPES).not.toContain(DESIGN_WORKSHOP_TYPE);
  expect(SEARCH_RECORD_TYPES).toEqual([...RECORD_TYPES, DESIGN_WORKSHOP_TYPE]);
});

test("the bucket name is spelled exactly as the API spells it", () => {
  // The server folds case for the comparison and echoes back its OWN spelling, so a client sending
  // `designworkshops` would get `designWorkshops` back and fail its own `types.includes` test.
  expect(DESIGN_WORKSHOP_TYPE).toBe("designWorkshops");
});

test("the media type list is the backend enum, in its order", () => {
  expect(SEARCH_MEDIA_TYPES).toEqual(["IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER"]);
});

/* ── reading a URL ────────────────────────────────────────────────────────── */

test("a design-workshop link read with the map's vocabulary yields no type at all", () => {
  const params = new URLSearchParams("type=designWorkshops");
  expect(filtersFromSearchParams(params).types).toEqual([]);
  expect(filtersFromSearchParams(params, SEARCH_RECORD_TYPES).types).toEqual([DESIGN_WORKSHOP_TYPE]);
});

test("the bucket name in a URL is matched case-insensitively", () => {
  // Nothing guarantees the case of a hand-edited or third-party link, and the whole vocabulary was
  // lower-case until this bucket existed — a plain `.toLowerCase()` filter silently dropped it.
  const params = new URLSearchParams("type=designworkshops");
  expect(filtersFromSearchParams(params, SEARCH_RECORD_TYPES).types).toEqual([DESIGN_WORKSHOP_TYPE]);
});

test("the three new filters survive a round trip through a link", () => {
  const params = new URLSearchParams("craftId=c1&artisanId=a1&mediaType=AUDIO");
  const read = filtersFromSearchParams(params);
  expect(read.craftId).toBe("c1");
  expect(read.artisanId).toBe("a1");
  expect(read.mediaType).toBe("AUDIO");
  const link = filtersToLinkParams(read);
  expect(link.craftId).toBe("c1");
  expect(link.artisanId).toBe("a1");
  expect(link.mediaType).toBe("AUDIO");
});

/* ── building a request ───────────────────────────────────────────────────── */

test("an untouched filter sends nothing", () => {
  const params = searchFilterParams(EMPTY_SEARCH_FILTERS);
  // `buildQuery` drops undefined; an empty string would be sent, which is why these must be
  // undefined and not "".
  expect(params.craftId).toBeUndefined();
  expect(params.artisanId).toBeUndefined();
  expect(params.mediaType).toBeUndefined();
  expect(params.types).toBeUndefined();
});

test("the three filters reach the request", () => {
  const params = searchFilterParams({
    ...EMPTY_SEARCH_FILTERS,
    craftId: "c1",
    artisanId: "a1",
    mediaType: "IMAGE"
  });
  expect(params.craftId).toBe("c1");
  expect(params.artisanId).toBe("a1");
  expect(params.mediaType).toBe("IMAGE");
});

test("the canonical types string keeps bucket order whatever order the ticks went in", () => {
  const params = searchFilterParams({
    ...EMPTY_SEARCH_FILTERS,
    types: [DESIGN_WORKSHOP_TYPE, "artisans"]
  });
  expect(params.types).toBe(`artisans,${DESIGN_WORKSHOP_TYPE}`);
});

test("each of the three counts towards the hidden-filter badge", () => {
  expect(activeFilterCount(EMPTY_SEARCH_FILTERS)).toBe(0);
  expect(activeFilterCount({ ...EMPTY_SEARCH_FILTERS, craftId: "c1" })).toBe(1);
  expect(activeFilterCount({ ...EMPTY_SEARCH_FILTERS, artisanId: "a1", mediaType: "AUDIO" })).toBe(2);
  // A bare filter is a real question — the page searches on it with an empty text box.
  expect(hasActiveFilters({ ...EMPTY_SEARCH_FILTERS, mediaType: "AUDIO" })).toBe(true);
});

test("empty types still means every bucket, including the sixth", () => {
  expect(typeVisible(EMPTY_SEARCH_FILTERS, DESIGN_WORKSHOP_TYPE)).toBe(true);
  const narrowed = { ...EMPTY_SEARCH_FILTERS, types: ["artisans" as const] };
  expect(typeVisible(narrowed, DESIGN_WORKSHOP_TYPE)).toBe(false);
});

/* ── the two permission sets ──────────────────────────────────────────────── */

test("viewing design-workshop data is professor and above", () => {
  for (const role of EVERY_ROLE) {
    const expected = ["PROFESSOR", "ADMIN", "MASTER_ADMIN"].includes(role);
    expect(canViewDesignWorkshopData(asUser(role)), role).toBe(expected);
  }
  expect(canViewDesignWorkshopData(null)).toBe(false);
});

test("exporting it is admin and master admin, and is strictly narrower", () => {
  for (const role of EVERY_ROLE) {
    const expected = ["ADMIN", "MASTER_ADMIN"].includes(role);
    expect(canExportDesignWorkshopData(asUser(role)), role).toBe(expected);
  }
  // The population the split exists for: reads on screen, may not take it away.
  expect(canViewDesignWorkshopData(asUser("PROFESSOR"))).toBe(true);
  expect(canExportDesignWorkshopData(asUser("PROFESSOR"))).toBe(false);
  for (const role of DESIGN_WORKSHOP_DATA_EXPORT_ROLES) {
    expect(DESIGN_WORKSHOP_DATA_VIEW_ROLES).toContain(role);
  }
  expect(DESIGN_WORKSHOP_DATA_EXPORT_ROLES.length).toBeLessThan(DESIGN_WORKSHOP_DATA_VIEW_ROLES.length);
});

test("the new capability does not widen the designer set", () => {
  // The two sets are almost opposites and that is deliberate: running a workshop is WRITING inside
  // somebody's fortnight of work, this is READING a table of what a corpus recorded.
  expect(DESIGN_WORKSHOP_ROLES).toEqual(["DESIGNER", "ADMIN", "MASTER_ADMIN"]);
  expect(canRunDesignWorkshops(asUser("PROFESSOR"))).toBe(false);
  expect(canViewDesignWorkshopData(asUser("DESIGNER"))).toBe(false);
  // An inspector holds a grant on ONE workshop; this predicate opens every workshop there is.
  expect(canViewDesignWorkshopData(asUser("INSPECTOR"))).toBe(false);
});
