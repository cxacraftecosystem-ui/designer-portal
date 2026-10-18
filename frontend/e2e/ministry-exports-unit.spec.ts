import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  EVERY_SCHEME,
  FILES_REMOVED,
  currentPeriod,
  initialPeriod,
  ministryExportsEnabled,
  packControls,
  periodLabel,
  recentPeriods,
  schemesSummary,
  shouldPoll,
  type PackStatus
} from "@/lib/ministryExports";
import {
  MINISTRY_EXPORT_REFUSAL,
  MINISTRY_EXPORT_ROLES,
  OVERSIGHT_ASSIGNER_ROLES,
  ROUTE_GUARDS,
  canManageMinistryExports
} from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/*
  THE MINISTRY'S MONTHLY EXPORT PACKS, without a browser: the guard row and its refusal against the
  server's own constant, the polling rule, which controls each status draws, "Every scheme", the
  `?period=` the e-mail's link carries, and the 410 sentence.
*/

const ROOT = join(__dirname, "..", "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");
const SERVICE = read("backend/app/services/ministry_exports.py");

const user = (role: UserRole, features?: Record<string, unknown>) =>
  ({ id: "u1", email: "u@example.org", name: "U", role, features }) as unknown as User;

test("the guard row exists, is a ministry surface, and refuses in the server's words", () => {
  const row = ROUTE_GUARDS.find((guard) => guard.path === "/ministry-exports");
  expect(row).toBeTruthy();
  expect(row?.ministry).toBe(true);
  expect(row?.gate).toContain("require_ministry_export_manager");
  expect(row?.message).toBe(MINISTRY_EXPORT_REFUSAL);
  const server = /^REFUSAL = "([^"]+)"$/m.exec(SERVICE);
  expect(server?.[1]).toBe(MINISTRY_EXPORT_REFUSAL);
});

test("only the whole-estate tiers manage the packs", () => {
  expect([...MINISTRY_EXPORT_ROLES].sort()).toEqual([...OVERSIGHT_ASSIGNER_ROLES].sort());
  for (const role of ["MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"] as UserRole[]) {
    expect(canManageMinistryExports(user(role))).toBe(true);
  }
  for (const role of ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "PROFESSOR", "DESIGNER"] as UserRole[]) {
    expect(canManageMinistryExports(user(role))).toBe(false);
  }
});

test("the feature reads off the session payload and is off by absence", () => {
  expect(ministryExportsEnabled(user("ADMIN"))).toBe(false);
  expect(ministryExportsEnabled(user("ADMIN", { ministryExports: false }))).toBe(false);
  expect(ministryExportsEnabled(user("ADMIN", { ministryExports: true }))).toBe(true);
  expect(ministryExportsEnabled(null)).toBe(false);
});

test("polling runs while anything is queued or building and stops once all are terminal", () => {
  const packs = (...statuses: PackStatus[]) => statuses.map((status) => ({ status }));
  expect(shouldPoll(packs("READY", "QUEUED"))).toBe(true);
  expect(shouldPoll(packs("BUILDING"))).toBe(true);
  expect(shouldPoll(packs("READY", "EMPTY", "FAILED"))).toBe(false);
  expect(shouldPoll([])).toBe(false);
});

test("Excel and PDF appear only when ready; Regenerate only once finished", () => {
  const files = { xlsx: { name: "a.xlsx", sizeBytes: 1 }, pdf: { name: "a.pdf", sizeBytes: 1 } };
  expect(packControls({ status: "READY", files })).toEqual({ download: true, regenerate: true });
  expect(packControls({ status: "READY", files: null })).toEqual({ download: false, regenerate: true });
  for (const status of ["EMPTY", "FAILED"] as PackStatus[]) {
    expect(packControls({ status, files: null })).toEqual({ download: false, regenerate: true });
  }
  for (const status of ["QUEUED", "BUILDING"] as PackStatus[]) {
    expect(packControls({ status, files: null })).toEqual({ download: false, regenerate: false });
  }
});

test("an empty scheme selection reads Every scheme", () => {
  const schemes = [
    { key: "SKILL_UPGRADATION", label: "Skill Upgradation" },
    { key: "OTHER", label: "Other" }
  ];
  expect(schemesSummary([], schemes)).toBe(EVERY_SCHEME);
  expect(EVERY_SCHEME).toBe("Every scheme");
  expect(schemesSummary(["OTHER", "SKILL_UPGRADATION"], schemes)).toBe("Skill Upgradation, Other");
});

test("?period= preselects the month, and a bad one falls back", () => {
  const now = new Date("2026-10-18T06:00:00Z");
  expect(initialPeriod("2026-09", ["2026-10"], now)).toBe("2026-09");
  expect(initialPeriod("Sept", ["2026-08"], now)).toBe("2026-08");
  expect(initialPeriod(null, [], now)).toBe("2026-09");
  expect(periodLabel("2026-09")).toBe("September 2026");
});

test("months are India time, and the generator offers this month and the twelve before", () => {
  // 30 Sep 19:00 UTC is 1 Oct 00:30 IST.
  expect(currentPeriod(new Date("2026-09-30T19:00:00Z"))).toBe("2026-10");
  const months = recentPeriods(13, new Date("2026-01-10T00:00:00Z"));
  expect(months[0]).toBe("2026-01");
  expect(months[1]).toBe("2025-12");
  expect(months).toHaveLength(13);
});

test("the 410 sentence is the server's, word for word", () => {
  const server = /^FILES_REMOVED = "([^"]+)"$/m.exec(SERVICE);
  expect(server?.[1]).toBe(FILES_REMOVED);
});

test("the page reads ?period=, polls, and draws no gap admission", () => {
  const page = read("frontend/app/(protected)/ministry-exports/page.tsx");
  expect(page).toContain('search.get("period")');
  expect(page).toContain("shouldPoll(packs)");
  expect(page.toLowerCase()).not.toMatch(/coming soon|not built|not yet available/);
});
