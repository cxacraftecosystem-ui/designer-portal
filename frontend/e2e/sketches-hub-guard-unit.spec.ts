import { test, expect } from "@playwright/test";

import { NAV_ITEMS } from "@/components/DynamicIslandNav";
import { canAccessRoute, canRunDesignWorkshops, routeGuardFor } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

const HUB = "/sketches-and-prototypes";

const ROLES: UserRole[] = [
  "MASTER_ADMIN",
  "ADMIN",
  "MINISTRY_ADMIN",
  "REGIONAL_DIRECTOR",
  "ASSISTANT_DIRECTOR",
  "PROFESSOR",
  "INSPECTOR",
  "DESIGNER",
  "RESEARCHER",
  "FIELD_CONTRIBUTOR",
  "CROWDSOURCE_VOLUNTEER"
];

const user = (role: UserRole): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

test.describe("who may open the sketches and prototypes hub", () => {
  test("the three roles that run design workshops may open it", () => {
    expect(canAccessRoute(user("DESIGNER"), HUB)).toBe(true);
    expect(canAccessRoute(user("ADMIN"), HUB)).toBe(true);
    expect(canAccessRoute(user("MASTER_ADMIN"), HUB)).toBe(true);
  });

  test("an inspector is refused, and outranking a designer is exactly why that needs saying", () => {
    expect(canAccessRoute(user("INSPECTOR"), HUB)).toBe(false);
  });

  test("a professor is refused, even though a professor outranks a designer elsewhere", () => {
    expect(canAccessRoute(user("PROFESSOR"), HUB)).toBe(false);
  });

  test("nobody below a designer may open it, and neither may a signed-out reader", () => {
    for (const role of ["RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"] as UserRole[]) {
      expect(canAccessRoute(user(role), HUB)).toBe(false);
    }
    expect(canAccessRoute(null, HUB)).toBe(false);
    expect(canAccessRoute(undefined, HUB)).toBe(false);
  });

  test("the rule is this path's own row and not some prefix that happens to reach it", () => {
    expect(routeGuardFor(HUB)?.path).toBe(HUB);
    expect(routeGuardFor("/design-workshops/dw_1/sketches-and-prototypes")?.path).toBe("/design-workshops");
  });

  test("the guard covers the query-less path the chooser writes, and anything nested under it", () => {
    expect(canAccessRoute(user("PROFESSOR"), `${HUB}/anything`)).toBe(false);
    expect(routeGuardFor(`${HUB}-archive`)).toBeNull();
  });
});

test.describe("the menu and the guard table agree", () => {
  test("the hub's menu entry and its guard row are spelled identically", () => {
    const entry = NAV_ITEMS.find((item) => item.href === HUB);
    expect(entry, "the Sketches & prototypes entry is missing from NAV_ITEMS").toBeTruthy();
    expect(routeGuardFor(entry!.href)?.path).toBe(HUB);
  });

  test("no design-workshop destination is hidden in the menu and open at the URL", () => {
    const family = NAV_ITEMS.filter((item) => item.can === canRunDesignWorkshops);
    expect(family.map((item) => item.href)).toContain(HUB);
    expect(family.length).toBeGreaterThan(2);

    const open: string[] = [];
    for (const item of family) {
      for (const role of ROLES) {
        const account = user(role);
        if (item.can(account)) continue;
        if (canAccessRoute(account, item.href)) open.push(`${item.href} is hidden from ${role} and still reachable`);
      }
    }
    expect(open).toEqual([]);
  });
});
