import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ministryDeskFor } from "@/components/dashboard/ministryDesk";
import { DIRECTORATE_STEPS } from "@/components/guide/directorateSteps";
import { decisionActor, decisionLabel, type DwAiDecisionRecord } from "@/lib/aiLayers";
import { ROLES_BY_RANK, canAccessRoute, canManageAnnualPlan, canReadAnnualPlan } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * THREE SCREENS THAT USED TO NARRATE A MISSING FEATURE, AND NOW HAVE IT (F9, F10, F11).
 *
 * - AI layers: a layer's whole decision history and one layer's text are read on their own.
 * - Sanction register: a co-designer's first link is re-issued from their own button, and an import
 *   hands back the same first links the form does.
 * - Annual plan: a Regional Director opens the plan narrowed to the states assigned to them.
 *
 * The server's half of each is asserted in backend/tests; this file holds the web's predicates and
 * the copy that must no longer say the feature is missing.
 */

const user = (role: UserRole): User => ({ id: `u-${role}`, email: "a@b.c", name: role, role }) as User;
const read = (path: string) => readFileSync(join(__dirname, "..", path), "utf8");

test("a Regional Director may open the annual plan, and only a manager may run it", () => {
  for (const role of ROLES_BY_RANK) {
    const expected = role === "REGIONAL_DIRECTOR" || canManageAnnualPlan(user(role));
    expect(canReadAnnualPlan(user(role)), role).toBe(expected);
    expect(canAccessRoute(user(role), "/annual-plan"), role).toBe(expected);
  }
  expect(canManageAnnualPlan(user("REGIONAL_DIRECTOR"))).toBe(false);
  expect(canReadAnnualPlan(user("ASSISTANT_DIRECTOR"))).toBe(false);
  expect(canReadAnnualPlan(null)).toBe(false);
});

test("the Regional Director's desk carries the annual plan and the Assistant Director's does not", () => {
  expect(ministryDeskFor(user("REGIONAL_DIRECTOR")).map((row) => row.href)).toContain("/annual-plan");
  expect(ministryDeskFor(user("ASSISTANT_DIRECTOR")).map((row) => row.href)).not.toContain("/annual-plan");
});

test("a decline is a decision in the history, and the actor is named in words", () => {
  expect(decisionLabel("DECLINED")).toBe("Declined");
  expect(decisionLabel("ACCEPTED")).toBe("Accepted");
  const entry = (patch: Partial<DwAiDecisionRecord>): DwAiDecisionRecord => ({
    id: "d",
    layerId: "l",
    decision: "ACCEPTED",
    note: null,
    actorId: "u-1",
    createdAt: null,
    ...patch
  });
  expect(decisionActor(entry({}), "u-1")).toBe("you");
  expect(decisionActor(entry({ actorName: "Asha Rao" }), "u-2")).toBe("Asha Rao");
  expect(decisionActor(entry({ actorName: null }), "u-2")).toBe("another account");
  expect(decisionActor(entry({ actorId: null }), "u-2")).toBe("an account that no longer exists");
});

test("the screens no longer say these features are missing", () => {
  const panel = read("components/designworkshop/AiLayersPanel.tsx");
  expect(panel).not.toContain("no endpoint for the history");
  expect(panel).not.toContain("no way to read one layer");
  expect(panel).toContain("Read this layer");
  expect(panel).toContain("Show the decision history");

  const guide = DIRECTORATE_STEPS.flatMap((step) => [...(step.watch ?? []), step.why ?? ""]).join("\n");
  expect(guide).not.toContain("no route in this product that can re-issue");
  expect(guide).not.toContain("AN IMPORT ISSUES NO SIGN-IN LINKS");
  expect(guide).not.toContain("scope table, not a promotion");

  const report = read("app/(protected)/sanction-orders/SanctionImportReport.tsx");
  expect(report).not.toContain("An import issues none");
  expect(report).toContain("sign-in links issued");

  const register = read("app/(protected)/sanction-orders/page.tsx");
  expect(register).toContain("issueSanctionCredentialLink(order.id, designer.designerUserId)");
});

test("a Regional Director's annual plan hides the national acts", () => {
  const page = read("app/(protected)/annual-plan/page.tsx");
  expect(page).toContain("const manager = canManageAnnualPlan(user)");
  expect(page).toContain("{manager ? <RegionalStatesPanel /> : null}");
  expect(page).toContain('manager && entry.standing === "PLANNED"');
  expect(page).toContain('manager && entry.standing === "WITHDRAWN"');
});
