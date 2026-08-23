/**
 * THE GUARD ROW IN FRONT OF `/sketches-and-prototypes`, AND THE INVARIANT THAT KEEPS ANY MENU ENTRY
 * FROM BEING MISTAKEN FOR ONE.
 *
 * ── WHY A TEST FOR ONE ROW OF A TABLE ───────────────────────────────────────────────────────────
 *
 * This family has shipped a top-level page with NO route guard three times — `/design-workshops`
 * itself, then `/design-review`, then this one — and `frontend/lib/permissions.ts` records why each
 * time: a maintainer read the §5 table in `docs/PERMISSIONS.md`, found nothing beside the
 * design-workshop tree, believed its closing sentence ("anything unlisted is open to any signed-in
 * user") and shipped a page whose URL was open to every account with a login. The nav entry was
 * hidden, which reads like a gate and is not one: `DynamicIslandNav` decides what to DRAW, and a
 * pasted URL never asks it.
 *
 * The mechanical checks that exist do not close that hole. `docs/tools/check-docs.mjs` diffs
 * `ROUTE_GUARDS` against the §5 table in both directions, so it catches a row in one file and not
 * the other — and stays green when BOTH are deleted together, which is exactly the shape of the
 * edit that reopens this. Nothing else asserts that the guard exists at all: the predicate
 * `canRunDesignWorkshops` is covered elsewhere, but a predicate nothing applies to a path refuses
 * nobody.
 *
 * ── WHAT THE SECOND HALF OF THIS FILE IS FOR ────────────────────────────────────────────────────
 *
 * The last test is not about this page alone. It pins the rule the three regressions broke, across
 * every menu destination that carries `canRunDesignWorkshops`: if the menu will not SHOW one of them
 * to a role, `ROUTE_GUARDS` must also refuse that role the PATH. That is "a hidden nav entry is not a
 * guard" turned into an assertion, and it fails for any future page added to the menu beside these
 * behind that predicate without a row of its own — which is how all three of them arrived.
 *
 * It is that predicate rather than the whole menu ON PURPOSE, and the test says why at length: the
 * unscoped version of this assertion is false here, because several READ surfaces are hidden from
 * accounts that may legitimately open them and §5 calls an open URL the correct default for a page
 * that only reads. These paths all write.
 *
 * `item.can(user)` is read directly rather than through `isNavItemVisible`, and the difference is
 * deliberate: that helper additionally hides admin-tier chrome from an admin who has admin view
 * switched OFF, which is a display preference and not an entitlement. Folding it in here would
 * demand a route guard against the account's own toggle — a lock the toggle-holder can open — and
 * would say nothing about who may reach the page.
 */

import { test, expect } from "@playwright/test";

import { NAV_ITEMS } from "@/components/DynamicIslandNav";
import { canAccessRoute, canRunDesignWorkshops, routeGuardFor } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

const HUB = "/sketches-and-prototypes";

const ROLES: UserRole[] = [
  "MASTER_ADMIN",
  "ADMIN",
  "PROFESSOR",
  "DESIGNER",
  "RESEARCHER",
  "FIELD_CONTRIBUTOR",
  "CROWDSOURCE_VOLUNTEER"
];

/** The role is the whole fixture: every predicate this file exercises reads nothing else. */
const user = (role: UserRole): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

test.describe("who may open the sketches and prototypes hub", () => {
  test("the three roles that run design workshops may open it", () => {
    // A SET AND NOT A RANK THRESHOLD, which is the fact §2's ladder cannot give a reader and the
    // reason this page keeps being shipped unguarded.
    expect(canAccessRoute(user("DESIGNER"), HUB)).toBe(true);
    expect(canAccessRoute(user("ADMIN"), HUB)).toBe(true);
    expect(canAccessRoute(user("MASTER_ADMIN"), HUB)).toBe(true);
  });

  test("a professor is refused, even though a professor outranks a designer elsewhere", () => {
    // THE ASSERTION THE WHOLE FILE EXISTS FOR. Removing the `ROUTE_GUARDS` row leaves `routeGuardFor`
    // with nothing to return, `canAccessRoute` then answers true for everybody, and this is the line
    // that says so. The page's own `canRunDesignWorkshops` check would still refuse to render — but
    // the guard is what stops the shell, and the shell of this page is a list of workshop NAMES.
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
    // `routeGuardFor` is longest-match, so a row could be shadowed or — worse — believed to exist
    // because a broader rule is doing the refusing. Pinning `path` means a future edit that deletes
    // this row and relies on something else covering the URL fails here rather than in production.
    expect(routeGuardFor(HUB)?.path).toBe(HUB);
    // The path is top-level BECAUSE the workshop is chosen on the page, so the design-workshop
    // prefix cannot cover it; this is that asymmetry as an assertion.
    expect(routeGuardFor("/design-workshops/dw_1/sketches-and-prototypes")?.path).toBe("/design-workshops");
  });

  test("the guard covers the query-less path the chooser writes, and anything nested under it", () => {
    // `?workshop=` and `?tab=` are not part of a pathname, so the guard sees the same string either
    // way; asserted so that a future rule written with a query in it is caught here. Nesting is
    // checked because `routeMatches` is segment-wise: a child page added under this hub inherits the
    // refusal, and a SIBLING whose name merely starts with the same letters must not.
    expect(canAccessRoute(user("PROFESSOR"), `${HUB}/anything`)).toBe(false);
    expect(routeGuardFor(`${HUB}-archive`)).toBeNull();
  });
});

test.describe("the menu and the guard table agree", () => {
  test("the hub's menu entry and its guard row are spelled identically", () => {
    // Two literals in two files, and a divergence is invisible in review: the entry would still be
    // drawn, the page would still work for a designer, and the URL would be open. String equality is
    // the only thing that catches a rename of one of them.
    const entry = NAV_ITEMS.find((item) => item.href === HUB);
    expect(entry, "the Sketches & prototypes entry is missing from NAV_ITEMS").toBeTruthy();
    expect(routeGuardFor(entry!.href)?.path).toBe(HUB);
  });

  test("no design-workshop destination is hidden in the menu and open at the URL", () => {
    /*
      THE GENERAL RULE, OVER THE FAMILY IT KEEPS BREAKING IN. A hidden entry is a decision about
      drawing; a refusal is a decision about reaching. Where the first exists without the second the
      URL is open — three times in this family already — so every menu entry carrying THIS predicate
      is required to have a `ROUTE_GUARDS` rule that refuses the same accounts. A sibling added to
      the menu beside these without a row of its own fails here, which is the whole point: that is
      the edit that has shipped the hole every time.

      SCOPED TO `canRunDesignWorkshops` BY IDENTITY, and not written as "every menu entry", because
      the unscoped version is FALSE in this repository and would have to be suppressed with a list of
      exemptions that the next reader would grow. `/crafts`, `/products`, `/processes`, `/tools` and
      `/workshops` are all hidden from accounts that may still open them, deliberately: they are READ
      surfaces whose menu predicate names who has business with them rather than who may look, and
      §5's closing sentence says an unlisted path is open to any signed-in user precisely because
      that is the right default for a page that only reads. What is NOT allowed is that arrangement
      for a page that writes — which is every path this predicate guards, the upload half of this one
      included.
    */
    const family = NAV_ITEMS.filter((item) => item.can === canRunDesignWorkshops);
    // A guard against the test quietly becoming vacuous if the predicate is ever wrapped or inlined
    // at the call site: an empty family would pass the loop below without asserting anything.
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
