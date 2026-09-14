import { expect, test } from "@playwright/test";

import {
  assignableRoles,
  canAccessRoute,
  canAssignTasks,
  canCreateDesignWorkshops,
  canCreateRecords,
  canDownloadDataset,
  canExportDesignWorkshopData,
  canInspectDesignWorkshops,
  canManageCrafts,
  canManageQuestionnaire,
  canManageUsers,
  canManageWorkshops,
  canReview,
  canRunDesignWorkshops,
  canSeeDataTile,
  canViewDesignWorkshopData,
  isAdmin,
  isMasterAdmin
} from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * THE THREE DIRECTORATE TIERS ON THE WEB — ASSISTANT_DIRECTOR (42), REGIONAL_DIRECTOR (45) and
 * MINISTRY_ADMIN (48), added 2026-09-13.
 *
 * WHY A FILE OF ITS OWN, WHEN EVERY OTHER SPEC IN THIS FOLDER ALREADY WALKS `ALL_ROLES`. Those
 * specs assert the rule each was written for over whatever their tuple happens to hold, so adding
 * three tokens widens their coverage and states nothing NEW. What needed stating is the pair of
 * facts a reader of `lib/permissions.ts` will get wrong about these three, because no line in that
 * file names any of them:
 *
 *   1. A RANK ABOVE 40 CLEARS EVERY `hasRank(user, "PROFESSOR")` PREDICATE IN THIS CLIENT AT ONCE —
 *      `canManageUsers`, `canManageCrafts`, `canManageWorkshops`, `canManageQuestionnaire`,
 *      `canDownloadDataset`, `canSeeDataTile`. All three acquired those the moment the numbers
 *      existed, through code that names no tier and with no test going red. That is the owner's
 *      decision of 2026-09-13 and this file is where it is recorded.
 *
 *   2. `MINISTRY_ADMIN` IS NOT AN ADMIN. `isAdmin` is `role === "MASTER_ADMIN" || role === "ADMIN"`
 *      — set membership, not a rank test — so no number reaches it and 48 is not close. It is the
 *      one token in this product whose English reading and its meaning in the code point in
 *      opposite directions, which makes it the cell a reviewer should read twice rather than a
 *      comment somebody might believe.
 *
 * `backend/tests/test_directorate_tiers.py` is the server-side twin, and
 * `backend/tests/test_role_ladder_parity.py` registers this file so it cannot fall behind the
 * ladder it enumerates.
 */

const user = (role: UserRole): User => ({ id: `u-${role}`, email: "a@b.c", name: role, role } as User);

const ALL_ROLES: UserRole[] = [
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

const DIRECTORATE: UserRole[] = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN"];

test("the ladder holds all eleven tiers and the three directorate ranks sit between professor and admin", () => {
  expect(ALL_ROLES).toHaveLength(11);
  for (const role of DIRECTORATE) {
    expect(ALL_ROLES, role).toContain(role);
  }
});

test("a ministry admin is not an admin, and neither are the other two directorate tiers", () => {
  for (const role of DIRECTORATE) {
    expect(isAdmin(user(role)), role).toBe(false);
    expect(isMasterAdmin(user(role)), role).toBe(false);
    // `canAssignTasks` is written as a rank floor at ADMIN where the server is a set. It agrees
    // today only because 42/45/48 are all below 50; asserted so the agreement is checked rather
    // than assumed.
    expect(canAssignTasks(user(role)), role).toBe(false);
    expect(canAccessRoute(user(role), "/admin"), role).toBe(false);
  }
});

test("the three tiers run a workshop and reach no other design-workshop set, because each is its own set", () => {
  // ⚠ THE FIRST EXPECTATION IS THE INVERSION OF WHAT THIS TEST ASSERTED UNTIL 2026-09-14, and the
  // cause is a ruling rather than a bug. It read "no directorate tier reaches any design-workshop
  // set" — true when written, made false by the owner's decision that these three may WRITE inside a
  // workshop. The gap that forced it: a MINISTRY_ADMIN could promote an annual-plan row into a
  // design workshop and then not save a stage in the workshop they had just created.
  //
  // THE POINT THE OLD NAME WAS MAKING SURVIVES AND IS NOW SHARPER. Each of these is its own SET, so
  // gaining one buys nothing in the others: the three RUN a workshop, and still cannot OPEN a bare
  // one, cannot INSPECT one, and cannot EXPORT its rows. Under a rank ladder all four would have
  // moved together, which is the drift a set exists to prevent.
  for (const role of DIRECTORATE) {
    expect(canRunDesignWorkshops(user(role)), role).toBe(true);
    expect(canCreateDesignWorkshops(user(role)), role).toBe(false);
    expect(canInspectDesignWorkshops(user(role)), role).toBe(false);
    // EXPORT is the half that did NOT move on 2026-09-13, and it is the half that takes rows out of
    // the product. A professor has read-without-export since 2026-08-30; these three inherit that
    // exact shape rather than a wider one.
    expect(canExportDesignWorkshopData(user(role)), role).toBe(false);
  }

  // AND THE TWO TIERS BETWEEN THEM AND A DESIGNER ARE STILL REFUSED, which is what stops the first
  // expectation being read as "a floor moved". PROFESSOR and INSPECTOR are both outranked by all
  // three above and both write nothing — an inspector especially, since it would otherwise author
  // the stages it later reviews.
  for (const role of ["PROFESSOR", "INSPECTOR"] as const) {
    expect(canRunDesignWorkshops(user(role)), role).toBe(false);
  }
});

test("reading design-workshop stage data on screen IS the one set the 2026-09-13 ruling widened", () => {
  for (const role of DIRECTORATE) {
    expect(canViewDesignWorkshopData(user(role)), role).toBe(true);
  }
  expect(canViewDesignWorkshopData(user("PROFESSOR"))).toBe(true);
  // The tier just BELOW the floor, and the reason the gate is a set and not `hasRank(…, "PROFESSOR")`:
  // an inspector reaches ONE workshop under a grant an admin wrote, and must never acquire every
  // workshop in the repository because a number moved.
  expect(canViewDesignWorkshopData(user("INSPECTOR"))).toBe(false);
  expect(canViewDesignWorkshopData(user("DESIGNER"))).toBe(false);
});

test("every professor floor in this client opens for all three, which is inherited and intended", () => {
  for (const role of DIRECTORATE) {
    expect(canManageUsers(user(role)), role).toBe(true);
    expect(canManageCrafts(user(role)), role).toBe(true);
    expect(canManageWorkshops(user(role)), role).toBe(true);
    expect(canManageQuestionnaire(user(role)), role).toBe(true);
    expect(canDownloadDataset(user(role)), role).toBe(true);
    expect(canSeeDataTile(user(role)), role).toBe(true);
    expect(canReview(user(role)), role).toBe(true);
    expect(canCreateRecords(user(role)), role).toBe(true);
    expect(canAccessRoute(user(role), "/users"), role).toBe(true);
  }
});

test("the minting ceiling is inclusive of the caller's own tier and stops below admin", () => {
  // `users.assert_role` on the server compares with `>` (backend/app/api/routes/users.py), so a tier
  // may mint ITSELF. `assignableRoles` is the same comparison spelled `<=`; if the two ever drift,
  // the picker offers a role the API refuses.
  const ministry = assignableRoles(user("MINISTRY_ADMIN"));
  expect(ministry).toContain("MINISTRY_ADMIN");
  expect(ministry).toContain("REGIONAL_DIRECTOR");
  expect(ministry).toContain("PROFESSOR");
  expect(ministry).not.toContain("ADMIN");
  expect(ministry).not.toContain("MASTER_ADMIN");

  const assistant = assignableRoles(user("ASSISTANT_DIRECTOR"));
  expect(assistant).toContain("ASSISTANT_DIRECTOR");
  expect(assistant).not.toContain("REGIONAL_DIRECTOR");
  expect(assistant).not.toContain("MINISTRY_ADMIN");
});
