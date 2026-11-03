import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  AWAITING_APPROVAL_BADGE_HREF,
  AWAITING_SANCTION_BADGE_CLASS,
  AWAITING_SANCTION_BADGE_HREF,
  MINISTRY_DESK,
  awaitingApprovalSentence,
  awaitingSanctionSentence,
  ministryDeskFor
} from "@/components/dashboard/ministryDesk";
import { DIRECTORATE_STEPS } from "@/components/guide/directorateSteps";
import {
  APPROVAL_AUTHORITY_ROLES,
  MINISTRY_DESK_ROLES,
  canApproveDesignWorkshops,
  ROLES_BY_RANK,
  canAccessRoute,
  canSeeMinistryDesk,
  isAdmin,
  isMasterAdmin,
  roleRank
} from "@/lib/permissions";
import { canRecordSanctionOrders } from "@/lib/sanctionOrders";
import type { User, UserRole } from "@/lib/types";

const ROOT = join(__dirname, "..");
const NAV = join(ROOT, "components", "DynamicIslandNav.tsx");
const CARD = join(ROOT, "components", "dashboard", "MinistryDeskCard.tsx");
const PROTECTED = join(ROOT, "app", "(protected)");

const withoutComments = (source: string) => source.replace(/\/\*[\s\S]*?\*\//g, "");

const user = (role: UserRole): User => ({ id: `u-${role}`, email: "a@b.c", name: role, role }) as User;

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
  for (const role of ROLES_BY_RANK) {
    const u = user(role);
    if (isAdmin(u) && !isMasterAdmin(u)) {
      expect(canSeeMinistryDesk(u), `${role}: an admin is out by name`).toBe(false);
    }
  }
  const inAt48 = canSeeMinistryDesk(user("MINISTRY_ADMIN"));
  const outAt50 = canSeeMinistryDesk(user("ADMIN"));
  const inAt60 = canSeeMinistryDesk(user("MASTER_ADMIN"));
  expect([inAt48, outAt50, inAt60]).toEqual([true, false, true]);
  expect(roleRank(user("MINISTRY_ADMIN"))).toBeLessThan(roleRank(user("ADMIN")));
  expect(roleRank(user("ADMIN"))).toBeLessThan(roleRank(user("MASTER_ADMIN")));
});

test("a row is offered exactly when the route guard would admit it — every tier, every row", () => {
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
  for (const role of AUDIENCE) {
    expect(ministryDeskFor(user(role)).length, `${role} would see an empty ministry desk`).toBeGreaterThanOrEqual(2);
  }
});

test("the per-row gating is real: an assistant director's desk lacks the rows its tier cannot open", () => {
  const assistantHrefs = ministryDeskFor(user("ASSISTANT_DIRECTOR")).map((d) => d.href);
  expect(assistantHrefs).toContain("/officers/monitored");
  expect(assistantHrefs).not.toContain("/annual-plan");
  expect(assistantHrefs).not.toContain("/officers");

  const masterHrefs = ministryDeskFor(user("MASTER_ADMIN")).map((destination) => destination.href);
  expect(masterHrefs).toContain("/officers/monitored");
});

test("the desk's screens are the directorate walkthrough's screens, in its order", () => {
  expect(MINISTRY_DESK.map((destination) => destination.href)).toEqual(
    DIRECTORATE_STEPS.map((step) => step.href)
  );
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

test("every row on the desk opens a route that exists", () => {
  for (const destination of MINISTRY_DESK) {
    const dir = join(PROTECTED, destination.href.split("?")[0].replace(/^\//, ""));
    expect(existsSync(join(dir, "page.tsx")), `${destination.label} → ${destination.href}`).toBe(true);
  }
});

test("the desk ends with the sign-off, and the sign-off row, nav entry, deck card and guard share one predicate", () => {
  const last = MINISTRY_DESK[MINISTRY_DESK.length - 1];
  expect(last.href).toBe("/design-workshop-approvals");
  expect(last.label).toBe("Reports to approve");
  expect(last.can).toBe(canApproveDesignWorkshops);
  expect(AWAITING_APPROVAL_BADGE_HREF).toBe(last.href);
  for (const role of ROLES_BY_RANK) {
    expect(canAccessRoute(user(role), last.href), role).toBe(canApproveDesignWorkshops(user(role)));
  }
  expect([...APPROVAL_AUTHORITY_ROLES].sort()).toEqual(["MASTER_ADMIN", "MINISTRY_ADMIN"]);

  const nav = withoutComments(readFileSync(NAV, "utf8"));
  const after = nav.split(`href: "${AWAITING_APPROVAL_BADGE_HREF}"`)[1];
  expect(after, `NAV_ITEMS has no entry for ${AWAITING_APPROVAL_BADGE_HREF}`).toBeTruthy();
  const entry = after.split("href:")[0];
  expect(entry).toContain("can: canApproveDesignWorkshops");
  expect(entry).toContain("require_approving_authority");
});

test("the sign-off row is a Ministry Admin's and the master admin's, and no director's", () => {
  expect(ministryDeskFor(user("MINISTRY_ADMIN")).map((d) => d.href)).toContain("/design-workshop-approvals");
  expect(ministryDeskFor(user("MASTER_ADMIN")).map((d) => d.href)).toContain("/design-workshop-approvals");
  expect(ministryDeskFor(user("REGIONAL_DIRECTOR")).map((d) => d.href)).not.toContain("/design-workshop-approvals");
  expect(ministryDeskFor(user("ASSISTANT_DIRECTOR")).map((d) => d.href)).not.toContain("/design-workshop-approvals");
});

test("STANDING GUARD: nothing on the desk talks plumbing or promises a step for later", () => {
  const forbidden = /not built|\byet\b|endpoint|router|coming soon|\b(GET|POST|PUT|PATCH|DELETE)\b/i;
  for (const destination of MINISTRY_DESK) {
    expect(destination.note, destination.label).not.toMatch(forbidden);
    expect(destination.label, destination.label).not.toMatch(forbidden);
  }
  const rendered = withoutComments(readFileSync(CARD, "utf8")).replace(/\{\/\*[\s\S]*?\*\/\}/g, "");
  const text = [...rendered.matchAll(/>([^<>{}]+)</g)].map((m) => m[1]).join(" ");
  expect(text).not.toMatch(forbidden);
  expect(rendered).not.toMatch(/APPROVAL_GAP/);
});

test("the approval count's sentence says what it counts, in both plurals", () => {
  expect(awaitingApprovalSentence(1)).toBe("1 report is waiting for approval");
  expect(awaitingApprovalSentence(3)).toBe("3 reports are waiting for approval");
  expect(awaitingApprovalSentence(0)).toBe("0 reports are waiting for approval");
});

test("the approval badge is drawn by both renderers, each gated on its own row being on screen", () => {
  const nav = withoutComments(readFileSync(NAV, "utf8"));
  const card = withoutComments(readFileSync(CARD, "utf8"));
  for (const [name, source] of [
    ["the nav", nav],
    ["the desk card", card]
  ] as const) {
    expect(source, `${name} draws the badge`).toContain("useAwaitingApprovalCount");
    expect(source, `${name} must not fetch the count unconditionally`).not.toMatch(/useAwaitingApprovalCount\(\s*true\s*\)/);
  }
});

test("the badged destination is a real desk row, and its predicate is the endpoint's own mirror", () => {
  const badged = MINISTRY_DESK.find((destination) => destination.href === AWAITING_SANCTION_BADGE_HREF);
  expect(badged, `no desk row answers to ${AWAITING_SANCTION_BADGE_HREF}`).toBeTruthy();
  expect(badged!.can).toBe(canRecordSanctionOrders);
});

test("the nav entry the badge also sits on is gated by that same mirror", () => {
  const nav = withoutComments(readFileSync(NAV, "utf8"));
  const after = nav.split(`href: "${AWAITING_SANCTION_BADGE_HREF}"`)[1];
  expect(after, `NAV_ITEMS has no entry for ${AWAITING_SANCTION_BADGE_HREF}`).toBeTruthy();
  const entry = after.split("href:")[0];
  expect(entry, "the badged nav entry must be gated by the endpoint's own mirror").toContain(
    "can: canRecordSanctionOrders"
  );
  expect(entry, "…and must still say which server dependency that mirrors").toContain(
    'gate: "require_sanction_recorder"'
  );
});

test("the count's sentence says what it counts, in both plurals", () => {
  expect(awaitingSanctionSentence(1)).toBe("1 sanction order is awaiting its designer's details");
  expect(awaitingSanctionSentence(4)).toBe("4 sanction orders are awaiting their designers' details");
  for (const count of [1, 2, 17]) {
    expect(awaitingSanctionSentence(count)).toContain(String(count));
    expect(awaitingSanctionSentence(count), "the sentence names what is waiting").toMatch(/awaiting/i);
  }
});

test("one pill, two renderers: the class string is shared, and it is not a ministry rung", () => {
  const nav = withoutComments(readFileSync(NAV, "utf8"));
  const card = withoutComments(readFileSync(CARD, "utf8"));
  expect(nav).toContain("AWAITING_SANCTION_BADGE_CLASS");
  expect(card).toContain("AWAITING_SANCTION_BADGE_CLASS");
  expect(AWAITING_SANCTION_BADGE_CLASS).toContain("bg-amber-100");
  expect(AWAITING_SANCTION_BADGE_CLASS).toContain("text-amber-800");
  expect(AWAITING_SANCTION_BADGE_CLASS, "the badge is not painted from the ministry ramp").not.toContain(
    "ministry-"
  );
});

test("the badge reaches the sheet as well as the hover menu, and neither renderer fetches unconditionally", () => {
  const nav = withoutComments(readFileSync(NAV, "utf8"));
  const card = withoutComments(readFileSync(CARD, "utf8"));
  const drawn = (source: string, tag: string) => source.split(`<${tag}`).length - 1;
  expect(drawn(nav, "AwaitingSanctionBadge")).toBe(drawn(nav, "PendingAccessBadge"));
  expect(drawn(nav, "AwaitingSanctionBadge")).toBeGreaterThanOrEqual(2);
  for (const [name, source] of [["the nav", nav], ["the desk card", card]] as const) {
    expect(source, `${name} draws the badge`).toContain("useAwaitingSanctionCount");
    expect(source, `${name} must gate the fetch on what it renders`).not.toMatch(
      /useAwaitingSanctionCount\(\s*true\s*\)/
    );
  }
});

test("the card carries its own ministry stamp, on the panel element itself", () => {
  const opening = withoutComments(readFileSync(CARD, "utf8")).match(/<section\b[^>]*>/)?.[0] ?? "";
  expect(opening, "the card has a <section> root").toContain("<section");
  expect(opening, "the stamp belongs on the panel element itself, not on a wrapper").toContain(
    'className="panel mb-6 p-4 sm:p-5"'
  );
  expect(opening, "…and the attribute belongs on that same element").toContain('data-surface="ministry"');
});

test("the accent is a surface accent: the tiles turn, the focus ring does not", () => {
  const raw = readFileSync(CARD, "utf8");
  const rendered = withoutComments(raw);
  expect(raw, "the tile chip is a ministry ground now").not.toContain("bg-purple-50");
  expect(raw, "the walkthrough link is ministry ink now").not.toContain("text-purple-700");
  expect(raw, "the hover border is ministry now").not.toContain("hover:border-purple-300");
  expect(rendered, "the focus ring stays purple, deliberately").toContain("focus-visible:ring-purple-700");
  expect(rendered).toContain("bg-ministry-50");
  expect(rendered).toContain("hover:border-ministry-300");

  expect(rendered, "OQ-2: no action control on this card may wear the ministry ramp").not.toMatch(
    /(?:field-button|field-input)[^"]*\bministry-|\bministry-[^"]*(?:field-button|field-input)/
  );
});

test("every ministry ink and ground on the card carries its dark pair", () => {
  const painted = withoutComments(readFileSync(CARD, "utf8"))
    .split("\n")
    .filter((line) => line.includes("className"))
    .filter((line) =>
      (line.match(/[A-Za-z0-9:/[\]._-]+/g) ?? []).some(
        (token) => !token.startsWith("dark:") && /^(?:[a-z-]+:)*(?:text|bg)-ministry-/.test(token)
      )
    );
  expect(painted.length, "the card paints with the ministry ramp at all").toBeGreaterThanOrEqual(2);
  for (const line of painted) {
    expect(line.trim(), "ministry ink or ground with no dark: pair").toMatch(/dark:(?:text|bg)-ministry-/);
  }
});
