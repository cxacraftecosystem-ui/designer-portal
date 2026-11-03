import { readFileSync } from "node:fs";
import { join } from "node:path";

import { test, expect } from "@playwright/test";

import { canCreateDesignWorkshops, canRunDesignWorkshops, isAdmin } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

const PAGE = join(__dirname, "..", "app", "(protected)", "design-workshops", "page.tsx");
const source = readFileSync(PAGE, "utf8");

function user(role: UserRole): User {
  return { id: "u1", email: "someone@example.org", name: "Someone", role } as User;
}

test("administering who is on a workshop is admin-only, because every viewer route is", () => {
  expect(isAdmin(user("ADMIN"))).toBe(true);
  expect(isAdmin(user("MASTER_ADMIN"))).toBe(true);
  for (const role of [
    "MINISTRY_ADMIN",
    "REGIONAL_DIRECTOR",
    "ASSISTANT_DIRECTOR",
    "DESIGNER",
    "INSPECTOR",
    "PROFESSOR",
    "RESEARCHER",
    "FIELD_CONTRIBUTOR",
    "CROWDSOURCE_VOLUNTEER"
  ] as UserRole[]) {
    expect(`${role} may not administer viewers=${isAdmin(user(role))}`).toBe(`${role} may not administer viewers=false`);
  }
});

test("a designer is refused the access control and keeps everything else on the page", () => {
  expect(isAdmin(user("DESIGNER"))).toBe(false);
  expect(canRunDesignWorkshops(user("DESIGNER"))).toBe(true);

  expect(canCreateDesignWorkshops(user("DESIGNER"))).toBe(false);
  expect(isAdmin(user("ADMIN")) && canCreateDesignWorkshops(user("ADMIN"))).toBe(true);
});

test("the design workshop list mounts the existing picker rather than growing a second one", () => {
  expect(source).toContain('import { DesignWorkshopViewersPanel } from "@/components/settings/DesignWorkshopViewersPanel";');

  expect(
    /\{isAdmin\(user\) && adminMode \? \(\r?\n(?:(?!\) : null\})[\s\S])*?<DesignWorkshopViewersPanel refreshToken=\{viewersRefresh\} \/>/.test(
      source
    ),
    "DesignWorkshopViewersPanel must be inside the `isAdmin(user) && adminMode` conditional"
  ).toBe(true);

  expect(source.match(/<DesignWorkshopViewersPanel/g)?.length).toBe(1);
});

test("the viewers panel respects admin view; the delete control on this page does not (2026-10-21)", () => {
  expect(source).toContain("{isAdmin(user) && adminMode ? (");
  expect(source).toContain("{allowDelete ? (");
  expect(source).toContain("const allowDelete = isContentAdmin(user);");
  expect(source).not.toContain("{isAdmin(user) ? (");
});

test("the workshop's shareable code is offered from the list, for every row, under the row", () => {
  expect(source).toContain('<RecordCodeCard recordType="designWorkshop" id={workshop.id} title={workshop.title} />');
  expect(source).toContain('<td className="px-4 py-3" colSpan={6}>');
  expect(source).toContain('{codeFor?.id === workshop.id ? "Hide code" : "Show code"}');

  expect(source).toContain("if (codeFor && !rows.some((row) => row.id === codeFor.id)) setCodeFor(null);");
});
