import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  assignableRoles,
  canAccessRoute,
  canAssignTasks,
  canCreateDesignWorkshops,
  canCreateRecords,
  canDownloadDataset,
  canEditOthersRecord,
  canExportDesignWorkshopData,
  canInspectDesignWorkshops,
  canManageCrafts,
  canManageQuestionnaire,
  canManageUsers,
  canManageWorkshops,
  canProvisionAccounts,
  canReview,
  canRunDesignWorkshops,
  canSeeDataTile,
  canViewDesignWorkshopData,
  isAdmin,
  isMasterAdmin,
  provisionableRoles
} from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

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
    expect(canAssignTasks(user(role)), role).toBe(role === "MINISTRY_ADMIN");
    expect(canAccessRoute(user(role), "/admin"), role).toBe(false);
  }
});

test("the three tiers run a workshop and reach no other design-workshop set, because each is its own set", () => {
  for (const role of DIRECTORATE) {
    expect(canRunDesignWorkshops(user(role)), role).toBe(true);
    expect(canCreateDesignWorkshops(user(role)), role).toBe(role === "MINISTRY_ADMIN");
    expect(canInspectDesignWorkshops(user(role)), role).toBe(role === "MINISTRY_ADMIN");
    expect(canExportDesignWorkshopData(user(role)), role).toBe(false);
  }

  for (const role of ["PROFESSOR", "INSPECTOR"] as const) {
    expect(canRunDesignWorkshops(user(role)), role).toBe(false);
  }
});

test("reading design-workshop stage data on screen IS the one set the 2026-09-13 ruling widened", () => {
  for (const role of DIRECTORATE) {
    expect(canViewDesignWorkshopData(user(role)), role).toBe(true);
  }
  expect(canViewDesignWorkshopData(user("PROFESSOR"))).toBe(true);
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

test("account provisioning is a set: the ministry admin and both admin tiers, and nobody else", () => {
  const PROVISIONERS: UserRole[] = ["MASTER_ADMIN", "ADMIN", "MINISTRY_ADMIN"];
  for (const role of ALL_ROLES) {
    expect(canProvisionAccounts(user(role)), role).toBe(PROVISIONERS.includes(role));
  }
  expect(isAdmin(user("MINISTRY_ADMIN"))).toBe(false);

  for (const role of ["REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR", "PROFESSOR"] as const) {
    expect(canProvisionAccounts(user(role)), role).toBe(false);
    expect(provisionableRoles(user(role)), role).toEqual([]);
    expect(canManageUsers(user(role)), role).toBe(true);
  }

  const ministry = provisionableRoles(user("MINISTRY_ADMIN"));
  expect(ministry).toEqual(assignableRoles(user("MINISTRY_ADMIN")).filter((role) => role !== "MINISTRY_ADMIN"));
  expect(ministry).not.toContain("MINISTRY_ADMIN");
  expect(ministry).toContain("REGIONAL_DIRECTOR");
  expect(provisionableRoles(user("ADMIN"))).toContain("MINISTRY_ADMIN");
  expect(provisionableRoles(user("ADMIN"))).not.toContain("ADMIN");
  expect(provisionableRoles(user("MASTER_ADMIN"))).toContain("ADMIN");
  expect(provisionableRoles(user("MASTER_ADMIN"))).not.toContain("MASTER_ADMIN");
});

test("the review queue's Edit is the server's can_edit_others_record: Professor and above, over a creator below", () => {
  expect(canEditOthersRecord(user("INSPECTOR"), "DESIGNER")).toBe(false);
  expect(canEditOthersRecord(user("RESEARCHER"), "FIELD_CONTRIBUTOR")).toBe(false);
  expect(canEditOthersRecord(user("DESIGNER"), "RESEARCHER")).toBe(false);
  expect(canEditOthersRecord(user("PROFESSOR"), "INSPECTOR")).toBe(true);
  expect(canEditOthersRecord(user("PROFESSOR"), "PROFESSOR"), "a peer is not below").toBe(false);
  for (const role of DIRECTORATE) {
    expect(canEditOthersRecord(user(role), "PROFESSOR"), role).toBe(true);
  }
  expect(canEditOthersRecord(user("ASSISTANT_DIRECTOR"), "REGIONAL_DIRECTOR")).toBe(false);
  expect(canEditOthersRecord(user("ADMIN"), "ADMIN")).toBe(false);
  expect(canEditOthersRecord(user("MASTER_ADMIN"), "MASTER_ADMIN")).toBe(true);
  expect(canEditOthersRecord(user("PROFESSOR"), null)).toBe(true);
  expect(canEditOthersRecord(user("PROFESSOR"), "NOT_A_ROLE")).toBe(true);
  expect(canEditOthersRecord(null, "DESIGNER")).toBe(false);

  const page = readFileSync(join(__dirname, "..", "app", "(protected)", "review", "page.tsx"), "utf8");
  expect(page).toContain("canEditOthersRecord(user, item.createdBy?.role)");
  expect(page).toContain("{!offersEdit(item) ? null : (");
  expect(page).not.toContain("editLocked(item) || reviewEditableFields(item.recordType).length === 0 ? null");
});
