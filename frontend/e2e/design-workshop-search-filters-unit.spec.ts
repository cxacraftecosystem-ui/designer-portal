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
import { UNIFIED_TYPES } from "@/lib/unifiedSearch";

function asUser(role: UserRole): User {
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
  "ASSISTANT_DIRECTOR",
  "REGIONAL_DIRECTOR",
  "MINISTRY_ADMIN",
  "ADMIN",
  "MASTER_ADMIN"
];

test("the map keeps five buckets and search gets six", () => {
  expect(RECORD_TYPES).toEqual(["artisans", "workshops", "products", "tools", "media"]);
  expect(RECORD_TYPES).not.toContain(DESIGN_WORKSHOP_TYPE);
  expect(SEARCH_RECORD_TYPES).toEqual([...RECORD_TYPES, DESIGN_WORKSHOP_TYPE]);
});

test("the bucket name is spelled exactly as the API spells it", () => {
  expect(DESIGN_WORKSHOP_TYPE).toBe("designWorkshops");
});

test("the media type list is the backend enum, in its order", () => {
  expect(SEARCH_MEDIA_TYPES).toEqual(["IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER"]);
});

test("a design-workshop link read with the map's vocabulary yields no type at all", () => {
  const params = new URLSearchParams("type=designWorkshops");
  expect(filtersFromSearchParams(params).types).toEqual([]);
  expect(filtersFromSearchParams(params, SEARCH_RECORD_TYPES).types).toEqual([DESIGN_WORKSHOP_TYPE]);
});

test("the bucket name in a URL is matched case-insensitively", () => {
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

test("an untouched filter sends nothing", () => {
  const params = searchFilterParams(EMPTY_SEARCH_FILTERS);
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
  expect(hasActiveFilters({ ...EMPTY_SEARCH_FILTERS, mediaType: "AUDIO" })).toBe(true);
});

test("empty types still means every bucket, including the sixth", () => {
  expect(typeVisible(EMPTY_SEARCH_FILTERS, DESIGN_WORKSHOP_TYPE)).toBe(true);
  const narrowed = { ...EMPTY_SEARCH_FILTERS, types: ["artisans" as const] };
  expect(typeVisible(narrowed, DESIGN_WORKSHOP_TYPE)).toBe(false);
});

test("viewing design-workshop data is professor and above, which since 2026-09-13 includes the three directorate tiers", () => {
  for (const role of EVERY_ROLE) {
    const expected = [
      "PROFESSOR",
      "ASSISTANT_DIRECTOR",
      "REGIONAL_DIRECTOR",
      "MINISTRY_ADMIN",
      "ADMIN",
      "MASTER_ADMIN"
    ].includes(role);
    expect(canViewDesignWorkshopData(asUser(role)), role).toBe(expected);
  }
  expect(canViewDesignWorkshopData(null)).toBe(false);
});

test("exporting it is admin and master admin, and is strictly narrower", () => {
  for (const role of EVERY_ROLE) {
    const expected = ["ADMIN", "MASTER_ADMIN"].includes(role);
    expect(canExportDesignWorkshopData(asUser(role)), role).toBe(expected);
  }
  expect(canViewDesignWorkshopData(asUser("PROFESSOR"))).toBe(true);
  expect(canExportDesignWorkshopData(asUser("PROFESSOR"))).toBe(false);
  for (const role of DESIGN_WORKSHOP_DATA_EXPORT_ROLES) {
    expect(DESIGN_WORKSHOP_DATA_VIEW_ROLES).toContain(role);
  }
  expect(DESIGN_WORKSHOP_DATA_EXPORT_ROLES.length).toBeLessThan(DESIGN_WORKSHOP_DATA_VIEW_ROLES.length);
});

test("the view capability still does not carry a write", () => {
  expect(canRunDesignWorkshops(asUser("PROFESSOR"))).toBe(false);
  expect(canRunDesignWorkshops(asUser("INSPECTOR"))).toBe(false);
  for (const role of DESIGN_WORKSHOP_ROLES) {
    if (role === "DESIGNER") continue;
    expect(canViewDesignWorkshopData(asUser(role)), role).toBe(true);
  }
  expect(canViewDesignWorkshopData(asUser("DESIGNER"))).toBe(false);
  expect(canViewDesignWorkshopData(asUser("INSPECTOR"))).toBe(false);
});

test("every filter bucket is a unified search type, so the type filter reaches /search/unified unchanged", () => {
  for (const type of SEARCH_RECORD_TYPES) expect(UNIFIED_TYPES).toContain(type);
  expect(searchFilterParams({ ...EMPTY_SEARCH_FILTERS, types: [DESIGN_WORKSHOP_TYPE, "artisans"] }).types).toBe(
    "artisans,designWorkshops"
  );
});
