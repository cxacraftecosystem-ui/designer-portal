import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { MINISTRY_DESK, ministryDeskFor } from "@/components/dashboard/ministryDesk";
import { DIRECTORATE_STEPS } from "@/components/guide/directorateSteps";
import {
  MINISTRY_DESK_ROLES,
  ROLES_BY_RANK,
  canAccessRoute,
  canSeeMinistryDesk,
  isAdmin,
  isMasterAdmin,
  roleRank
} from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * THE MINISTRY DESK — the dashboard card that gathers the three directorate posts' screens.
 *
 * ── WHY THIS FILE ENUMERATES NOTHING BY HAND ────────────────────────────────────────────────────
 *
 * It sweeps `ROLES_BY_RANK`, which is DERIVED from `ROLE_RANK` — itself a registered mirror held to
 * `deps.py` by `backend/tests/test_role_ladder_parity.py` and by `docs/tools/check-docs.mjs`. So a
 * twelfth tier reaches every assertion below on the day it lands, with nothing here to remember,
 * and this file adds no new hand-kept copy of the ladder to a repository that has spent a lot of
 * time removing them. The one literal is the card's audience itself, which is the subject.
 *
 * ── WHAT IS PINNED, AND WHY EACH IS THE FAILURE THAT ACTUALLY HAPPENS ───────────────────────────
 *
 *  1. THE AUDIENCE IS THE FOUR TIERS AND NOTHING ELSE. Every rank-shaped instinct gets this set
 *     wrong in a different direction: `isAdmin` admits one of the four and refuses three, and no
 *     rank floor produces it at all, because the set has a hole at ADMIN (50) with MASTER_ADMIN (60)
 *     above it. It is asserted as a set rather than as a floor for exactly that reason.
 *  2. THE CARD CANNOT OFFER A ROW THE ROUTE WOULD REFUSE. This is the assertion that matters most,
 *     and it is checked against `canAccessRoute` — the real guard table — for every tier and every
 *     row rather than against a copy of the predicates. A launcher that draws a tile onto a lock
 *     panel teaches the reader the product is broken; a launcher that hides a tile the route would
 *     serve hides a feature. Both directions fail here.
 *  3. THE ROWS ARE THE DIRECTORATE WALKTHROUGH'S SCREENS, IN ITS ORDER. A launcher and a lesson that
 *     disagree about one job teach two jobs, and a reader has no way to tell which is the product.
 *     This is also the cheapest possible guard against the defect this repository keeps paying for:
 *     a screen added to one surface and forgotten on the other.
 *  4. NOBODY IN THE AUDIENCE GETS AN EMPTY CARD. The component renders null on an empty desk rather
 *     than a panel headed "Your ministry desk" with nothing under it — but "renders null" and
 *     "nobody ever hits it" are different states, and the second is the one worth asserting.
 *  5. THE LABELS ARE THE NAV'S. Four of these five destinations have no Android counterpart to be
 *     parity with, so the web nav is the only registry there is, and a sixth spelling invented on a
 *     card is a name nobody's grep finds.
 */

const ROOT = join(__dirname, "..");
const NAV = join(ROOT, "components", "DynamicIslandNav.tsx");

const user = (role: UserRole): User => ({ id: `u-${role}`, email: "a@b.c", name: role, role }) as User;

/**
 * The card's audience, written out once.
 *
 * ⚠ NOT DERIVED FROM `MINISTRY_DESK_ROLES`, which would make this test a restatement of the thing it
 * is testing. Four tokens, typed on purpose, so a widening of that set has to be typed here too and
 * is therefore a decision somebody made rather than one that happened.
 */
const AUDIENCE: UserRole[] = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "MASTER_ADMIN"];

test("the desk is offered to exactly four tiers, and the literal says the same", () => {
  const offered = ROLES_BY_RANK.filter((role) => canSeeMinistryDesk(user(role)));
  expect([...offered].sort()).toEqual([...AUDIENCE].sort());
  expect([...MINISTRY_DESK_ROLES].sort()).toEqual([...AUDIENCE].sort());
});

test("a signed-out caller is not in the audience", () => {
  expect(canSeeMinistryDesk(null)).toBe(false);
  expect(canSeeMinistryDesk(undefined)).toBe(false);
});

test("the audience is not isAdmin, and it is not a rank floor either", () => {
  /*
    STATED AS TWO FAILED SHAPES RATHER THAN AS THE SET AGAIN, because the set is already asserted
    above and what a reader needs from this file is why it could not have been written more simply.

    `isAdmin` is set membership on the two admin tiers. It admits the master admin and refuses the
    three ministry posts — the audience this card exists for — so it is wrong in the loudest possible
    direction: the card would be invisible to everybody it was built for.

    A rank floor is wrong the other way. The tightest floor admitting ASSISTANT_DIRECTOR (42) also
    admits ADMIN (50), who is deliberately out: an admin already has the settings hub and every one
    of these destinations in the nav. There is no floor that skips 50 and keeps 60.
  */
  for (const role of ROLES_BY_RANK) {
    const u = user(role);
    if (isAdmin(u) && !isMasterAdmin(u)) {
      expect(canSeeMinistryDesk(u), `${role}: an admin is out by name`).toBe(false);
    }
  }
  // The hole in the ladder, asserted as a hole: a tier is in, the tier above it is out, and the tier
  // above THAT is in. No threshold can produce that, which is the whole argument for the set.
  const inAt48 = canSeeMinistryDesk(user("MINISTRY_ADMIN"));
  const outAt50 = canSeeMinistryDesk(user("ADMIN"));
  const inAt60 = canSeeMinistryDesk(user("MASTER_ADMIN"));
  expect([inAt48, outAt50, inAt60]).toEqual([true, false, true]);
  expect(roleRank(user("MINISTRY_ADMIN"))).toBeLessThan(roleRank(user("ADMIN")));
  expect(roleRank(user("ADMIN"))).toBeLessThan(roleRank(user("MASTER_ADMIN")));
});

test("a row is offered exactly when the route guard would admit it — every tier, every row", () => {
  /*
    THE ASSERTION THIS FILE EXISTS FOR. `canAccessRoute` reads `ROUTE_GUARDS`, which is the real
    table `AppShell` enforces above every page — not a copy of the predicates, and not the predicates
    the card happens to import. If somebody widens a row's `can` to "make the card look complete",
    or narrows a guard without touching the card, this goes red and names the tier and the route.

    It sweeps the whole ladder, not only the audience, because `ministryDeskFor` is a pure function
    and a row that would be wrongly offered to a designer is a row that is wrong.
  */
  for (const role of ROLES_BY_RANK) {
    const u = user(role);
    const open = new Set(ministryDeskFor(u).map((destination) => destination.href));
    for (const destination of MINISTRY_DESK) {
      expect(
        open.has(destination.href),
        `${role} · ${destination.href}: the card and ROUTE_GUARDS disagree`
      ).toBe(canAccessRoute(u, destination.href));
    }
  }
});

test("nobody in the audience is shown an empty desk", () => {
  /*
    The component returns null on an empty desk rather than drawing a headed panel with nothing in
    it. That branch is correct and it is also unreachable today, which are two different facts: the
    floor below is what makes "unreachable" a checked claim instead of a comment. Every tier in the
    audience clears the sanction register (Assistant Director and above) and the workshop set, so two
    is the minimum.
  */
  for (const role of AUDIENCE) {
    expect(ministryDeskFor(user(role)).length, `${role} would see an empty ministry desk`).toBeGreaterThanOrEqual(2);
  }
});

test("a master admin is offered the desk and not the officer's own read surface", () => {
  /*
    THE ROW THAT PROVES THE PER-ROW GATING IS REAL RATHER THAN DECORATIVE. `assert_oversight_surface`
    answers an admin and the master admin a 403 BY NAME — an admin scoped to their own oversight rows
    sees an empty page and reads it as a broken deployment — so the one destination on this card that
    a master admin cannot open is absent from their card and present on an Assistant Director's.

    If this ever flips, either the server's rule moved or somebody "completed" the card by widening a
    row, and the two need telling apart.
  */
  const masterHrefs = ministryDeskFor(user("MASTER_ADMIN")).map((destination) => destination.href);
  expect(masterHrefs).not.toContain("/officers/monitored");
  expect(ministryDeskFor(user("ASSISTANT_DIRECTOR")).map((d) => d.href)).toContain("/officers/monitored");
});

test("the desk's screens are the directorate walkthrough's screens, in its order", () => {
  expect(MINISTRY_DESK.map((destination) => destination.href)).toEqual(
    DIRECTORATE_STEPS.map((step) => step.href)
  );
  // And by name too, so a href kept while a label drifted still fails.
  expect(MINISTRY_DESK.map((destination) => destination.label)).toEqual(
    DIRECTORATE_STEPS.map((step) => step.label)
  );
});

test("the desk's labels are the nav's labels, character for character", () => {
  const nav = readFileSync(NAV, "utf8");
  for (const destination of MINISTRY_DESK) {
    expect(nav, `NAV_ITEMS has no entry labelled "${destination.label}"`).toContain(
      `label: "${destination.label}"`
    );
  }
});

test("the desk holds no duplicate destination", () => {
  const hrefs = MINISTRY_DESK.map((destination) => destination.href);
  expect(new Set(hrefs).size).toBe(hrefs.length);
});
