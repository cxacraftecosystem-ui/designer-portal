import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { CONTENT_ADMIN_ROLES, ROLE_RANK, isAdmin, isContentAdmin, mayDeleteQuestionnaire } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

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

test("a questionnaire form is deleted by its author or a content admin, and nobody else (22 Oct 2026)", () => {
  expect(mayDeleteQuestionnaire({ ...user("DESIGNER"), id: "author" }, "author")).toBe(true);
  for (const role of CONTENT_ADMINS) expect(mayDeleteQuestionnaire(user(role), "author"), role).toBe(true);
  for (const role of Object.keys(ROLE_RANK) as UserRole[]) {
    if (CONTENT_ADMINS.includes(role)) continue;
    expect(mayDeleteQuestionnaire(user(role), "author"), role).toBe(false);
  }
  expect(mayDeleteQuestionnaire(null, "author")).toBe(false);
  expect(mayDeleteQuestionnaire(user("DESIGNER"), null)).toBe(false);
  const form = read("app/(protected)/questionnaires/[id]/page.tsx");
  expect(form).toContain("{mayDeleteSittings ? (\n                      <button");
});

test("admin view, and with it every Delete, is offered to the content admins", () => {
  const provider = read("components/AdminViewProvider.tsx");
  expect(provider).toContain("const canAdmin = isContentAdmin(user);");
  expect(provider).toContain("available: canAdmin,");
  expect(provider).toContain("canDeleteRecords: canAdmin,");
});

test("no Delete in the app reads admin view any more", () => {
  for (const file of [
    "app/(protected)/artisans/page.tsx",
    "app/(protected)/products/page.tsx",
    "app/(protected)/tools/page.tsx",
    "app/(protected)/processes/page.tsx",
    "app/(protected)/workshops/page.tsx",
    "components/CollabPanel.tsx",
    "components/media/ExistingMedia.tsx",
    "components/records/RecordDangerZone.tsx"
  ]) {
    expect(read(file), file).not.toContain("adminMode");
  }
  for (const file of ["app/(protected)/artisans/page.tsx", "app/(protected)/products/page.tsx", "app/(protected)/tools/page.tsx"]) {
    expect(read(file), file).toContain("{canDeleteRecords ? (");
  }
});

test("a comment's author edits it and only a content admin deletes it", () => {
  const panel = read("components/CollabPanel.tsx");
  expect(panel).toContain("user && c.authorId === user.id && editingId !== c.id ?");
  expect(panel).toContain("user && canDeleteRecords ?");
  expect(panel).not.toContain("isAdmin(");
});

test("an attached file's remove control is drawn for content admins, admin view or not", () => {
  const media = read("components/media/ExistingMedia.tsx");
  expect(media).toContain("const mayRemove = !readOnly && canDeleteRecords;");
  expect(media).toContain("onRemove={!mayRemove || removingId === media.id ? undefined");
});

test("a form's deletes are its author's or an admin's", () => {
  const form = read("app/(protected)/questionnaires/[id]/page.tsx");
  expect(form).toContain("const mayDelete = mayEdit && mayDeleteQuestionnaire(user, form?.ownerId);");
  expect(form).toContain("const mayDeleteSittings = mayEdit && canDeleteRecords;");
  expect(form).toContain("mayRemove={mayDelete}");
  expect(form).toContain("apiFetch(`/questionnaires/${id}`, { method: \"DELETE\" })");
  expect(form).toContain("apiFetch(`/questionnaires/${id}/entries/${entryId}`, { method: \"DELETE\" })");
});

test("a deleted sitting is restored by a content admin in admin view; a form by its author too", () => {
  const form = read("app/(protected)/questionnaires/[id]/page.tsx");
  expect(form).toContain('"Its author or an admin can restore it."');
  expect(form).not.toContain("This cannot be undone.");
  expect(form).toContain("{mayDeleteSittings && adminMode ? (\n        <DeletedSittings");
  const list = read("app/(protected)/questionnaires/page.tsx");
  expect(list).toContain('{tab === "deleted" || adminMode || !canDeleteRecords ? (\n        <DeletedQuestionnairesCard ownOnly={!canDeleteRecords}');
  const lib = read("lib/questionnaireForms.ts");
  expect(lib).toContain('deletedOnly: params.deletedOnly ? "true" : undefined');
  expect(lib).toContain("/restore`, { method: \"POST\" }");
  expect(lib).toContain("/deleted-entries`");
});

test("a design workshop's Delete reaches the Ministry Admin", () => {
  const page = read("app/(protected)/design-workshops/page.tsx");
  expect(page).toContain("const allowDelete = isContentAdmin(user);");
  expect(page).toContain("{allowDelete ? (");
});

test("the new delete routes have controls, admin view or not", () => {
  const sanction = read("app/(protected)/sanction-orders/page.tsx");
  expect(sanction).toContain("onDelete={viewing && canDeleteRecords ? () => void deleteOrder(viewing) : undefined}");
  expect(read("lib/sanctionOrders.ts")).toContain("`/sanction-orders/${encodeURIComponent(id)}`, { method: \"DELETE\" }");
  const plan = read("app/(protected)/annual-plan/page.tsx");
  expect(plan).toContain('manager && canDeleteRecords && entry.standing !== "PROMOTED"');
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
