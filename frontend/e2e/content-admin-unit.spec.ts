import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { CONTENT_ADMIN_ROLES, ROLE_RANK, isAdmin, isContentAdmin } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * The content admins (owner's ruling, 2026-10-20): ADMIN, MASTER_ADMIN and MINISTRY_ADMIN add, edit
 * and delete every content type; every other role adds and edits and never deletes. Delete controls
 * read `adminMode`, which only a content admin can turn on, so the Ministry Admin gains Delete on
 * every page that already gates on it.
 *
 * Run: `npx playwright test e2e/content-admin-unit.spec.ts --reporter=line`
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

function user(role: UserRole): User {
  return { id: "u1", email: "someone@example.org", name: "Someone", role };
}

const CONTENT_ADMINS: UserRole[] = ["MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"];

test("the content admins are exactly the three admin tiers, and isAdmin is not widened", () => {
  expect([...CONTENT_ADMIN_ROLES].sort()).toEqual([...CONTENT_ADMINS].sort());
  for (const role of Object.keys(ROLE_RANK) as UserRole[]) {
    expect(isContentAdmin(user(role)), role).toBe(CONTENT_ADMINS.includes(role));
  }
  expect(isContentAdmin(null)).toBe(false);
  expect(isAdmin(user("MINISTRY_ADMIN"))).toBe(false);
});

test("admin view, and with it every Delete, is offered to the content admins", () => {
  const provider = read("components/AdminViewProvider.tsx");
  expect(provider).toContain("const canAdmin = isContentAdmin(user);");
  expect(provider).toContain("available: canAdmin,");
});

test("a comment's author edits it and only a content admin deletes it", () => {
  const panel = read("components/CollabPanel.tsx");
  expect(panel).toContain("user && c.authorId === user.id && editingId !== c.id ?");
  expect(panel).toContain("user && adminMode ?");
  expect(panel).not.toContain("isAdmin(");
});

test("an attached file's remove control is drawn only in admin view", () => {
  const media = read("components/media/ExistingMedia.tsx");
  expect(media).toContain("const mayRemove = !readOnly && adminMode;");
  expect(media).toContain("onRemove={!mayRemove || removingId === media.id ? undefined");
});

test("questionnaire deletes are content-admin only on both questionnaire screens", () => {
  const instrument = read("app/(protected)/questionnaire/page.tsx");
  expect(instrument).toContain("onRemove={adminMode ? () => removeQuestion(question) : undefined}");
  expect(instrument).toContain("{adminMode ? (\n                    <button type=\"button\" className=\"text-sm font-semibold text-red-700 dark:text-red-400\" onClick={() => removeSection(section)}>");
  const form = read("app/(protected)/questionnaires/[id]/page.tsx");
  expect(form).toContain("const mayDelete = mayEdit && adminMode;");
  expect(form).toContain("mayRemove={mayDelete}");
  expect(form).toContain("apiFetch(`/questionnaires/${id}`, { method: \"DELETE\" })");
  expect(form).toContain("apiFetch(`/questionnaires/${id}/entries/${entryId}`, { method: \"DELETE\" })");
});

test("a deleted questionnaire or sitting is restored by a content admin, in admin view", () => {
  const form = read("app/(protected)/questionnaires/[id]/page.tsx");
  expect(form).toContain('"An admin can restore it."');
  expect(form).not.toContain("This cannot be undone.");
  expect(form).toContain("{mayDelete ? (\n        <DeletedSittings");
  const list = read("app/(protected)/questionnaires/page.tsx");
  expect(list).toContain("{adminMode ? <DeletedQuestionnairesCard");
  const lib = read("lib/questionnaireForms.ts");
  expect(lib).toContain('deletedOnly: params.deletedOnly ? "true" : undefined');
  expect(lib).toContain("/restore`, { method: \"POST\" }");
  expect(lib).toContain("/deleted-entries`");
});

test("a design workshop's Delete reaches the Ministry Admin", () => {
  const page = read("app/(protected)/design-workshops/page.tsx");
  expect(page).toContain("const allowDelete = isContentAdmin(user);");
  expect(page).toContain("{allowDelete && adminMode ? (");
});

test("the new delete routes have controls, in admin view", () => {
  const sanction = read("app/(protected)/sanction-orders/page.tsx");
  expect(sanction).toContain("onDelete={viewing && adminMode ? () => void deleteOrder(viewing) : undefined}");
  expect(read("lib/sanctionOrders.ts")).toContain("`/sanction-orders/${encodeURIComponent(id)}`, { method: \"DELETE\" }");
  const plan = read("app/(protected)/annual-plan/page.tsx");
  expect(plan).toContain('manager && adminMode && entry.standing !== "PROMOTED"');
  expect(read("app/(protected)/annual-plan/annualPlan.ts")).toContain("`/annual-plan/entries/${encodeURIComponent(id)}`, { method: \"DELETE\" }");
});

test("a Ministry Admin restores, maps and transcribes where the server now lets them", () => {
  const workshops = read("app/(protected)/design-workshops/page.tsx");
  expect(workshops).toContain("{adminMode && !isAdmin(user) ? (");
  expect(workshops).toContain("<DeletedWorkshopsCard />");
  expect(read("app/(protected)/crafts/page.tsx")).toContain("{isContentAdmin(user) && adminMode ? <UnmappedCraftsPanel /> : null}");
  expect(read("app/(protected)/media/page.tsx")).toContain("const canTranscribe = isContentAdmin(user);");
  expect(read("app/(protected)/workshops/page.tsx")).toContain("{isContentAdmin(user) ? (\n        <div className=\"mb-5\">\n          <WorkshopMappingPanel />");
});
