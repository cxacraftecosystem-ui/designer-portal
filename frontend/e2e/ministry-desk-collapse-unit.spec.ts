import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  AWAITING_APPROVAL_BADGE_HREF,
  AWAITING_SANCTION_BADGE_HREF,
  MINISTRY_DESK,
  MINISTRY_DESK_EXPANDED_KEY,
  awaitingApprovalSentence,
  awaitingSanctionSentence,
  ministryDeskCountLabel,
  ministryDeskFor,
  ministryDeskSummary,
  readMinistryDeskExpanded,
  writeMinistryDeskExpanded
} from "@/components/dashboard/ministryDesk";
import type { User, UserRole } from "@/lib/types";

const CARD = join(__dirname, "..", "components", "dashboard", "MinistryDeskCard.tsx");
const withoutComments = (source: string) => source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\{\/\*[\s\S]*?\*\/\}/g, "");
const user = (role: UserRole): User => ({ id: `u-${role}`, email: "a@b.c", name: role, role }) as User;
const FULL = MINISTRY_DESK;

test("counts above zero are listed in desk order, each opening its own list", () => {
  const summary = ministryDeskSummary(FULL, { awaitingSanction: 3, awaitingApproval: 2 });
  expect(summary.kind).toBe("waiting");
  if (summary.kind !== "waiting") return;
  expect(summary.counts.map((item) => item.href)).toEqual([AWAITING_SANCTION_BADGE_HREF, AWAITING_APPROVAL_BADGE_HREF]);
  expect(summary.counts.map((item) => item.count)).toEqual([3, 2]);
  expect(summary.counts[0].label).toBe("sanction orders awaiting details");
  expect(summary.counts[1].label).toBe("reports waiting for approval");
  expect(summary.counts[0].sentence).toBe(awaitingSanctionSentence(3));
  expect(summary.counts[1].sentence).toBe(awaitingApprovalSentence(2));
});

test("a zero count is left off; only the counts that need someone are shown", () => {
  const summary = ministryDeskSummary(FULL, { awaitingSanction: 0, awaitingApproval: 1 });
  expect(summary).toEqual({
    kind: "waiting",
    counts: [
      {
        href: AWAITING_APPROVAL_BADGE_HREF,
        count: 1,
        label: "report waiting for approval",
        sentence: "1 report is waiting for approval"
      }
    ]
  });
});

test("both plurals of both labels", () => {
  expect(ministryDeskCountLabel(AWAITING_APPROVAL_BADGE_HREF, 1)).toBe("report waiting for approval");
  expect(ministryDeskCountLabel(AWAITING_APPROVAL_BADGE_HREF, 4)).toBe("reports waiting for approval");
  expect(ministryDeskCountLabel(AWAITING_SANCTION_BADGE_HREF, 1)).toBe("sanction order awaiting details");
  expect(ministryDeskCountLabel(AWAITING_SANCTION_BADGE_HREF, 12)).toBe("sanction orders awaiting details");
});

test("'Nothing waiting' only once every count the desk carries has answered zero", () => {
  expect(ministryDeskSummary(FULL, { awaitingSanction: 0, awaitingApproval: 0 })).toEqual({ kind: "clear" });
  expect(ministryDeskSummary(FULL, { awaitingSanction: 0, awaitingApproval: null })).toEqual({ kind: "unknown" });
  expect(ministryDeskSummary(FULL, { awaitingSanction: null, awaitingApproval: null })).toEqual({ kind: "unknown" });
  expect(ministryDeskSummary(FULL, { awaitingSanction: null, awaitingApproval: 5 }).kind).toBe("waiting");
});

test("a count for a row this desk does not carry is never shown or waited on", () => {
  const desk = ministryDeskFor(user("ASSISTANT_DIRECTOR"));
  expect(desk.some((row) => row.href === AWAITING_APPROVAL_BADGE_HREF)).toBe(false);
  expect(ministryDeskSummary(desk, { awaitingSanction: 0, awaitingApproval: null })).toEqual({ kind: "clear" });
  expect(ministryDeskSummary(desk, { awaitingSanction: 0, awaitingApproval: 9 })).toEqual({ kind: "clear" });
  const bare = FULL.filter(
    (row) => row.href !== AWAITING_SANCTION_BADGE_HREF && row.href !== AWAITING_APPROVAL_BADGE_HREF
  );
  expect(ministryDeskSummary(bare, { awaitingSanction: 0, awaitingApproval: 0 })).toEqual({ kind: "unknown" });
});

test("the expanded choice is remembered per browser, folded by default", () => {
  const store = new Map<string, string>();
  const storage = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value)
  };
  expect(readMinistryDeskExpanded(storage)).toBe(false);
  writeMinistryDeskExpanded(storage, true);
  expect(store.get(MINISTRY_DESK_EXPANDED_KEY)).toBe("1");
  expect(readMinistryDeskExpanded(storage)).toBe(true);
  writeMinistryDeskExpanded(storage, false);
  expect(readMinistryDeskExpanded(storage)).toBe(false);
});

test("blocked storage never throws: it reads folded and forgets quietly", () => {
  const blocked = {
    getItem: () => {
      throw new Error("SecurityError");
    },
    setItem: () => {
      throw new Error("QuotaExceededError");
    }
  };
  expect(readMinistryDeskExpanded(blocked)).toBe(false);
  expect(() => writeMinistryDeskExpanded(blocked, true)).not.toThrow();
  expect(readMinistryDeskExpanded(null)).toBe(false);
  expect(() => writeMinistryDeskExpanded(undefined, true)).not.toThrow();
});

test("the fold is phone-only, and the control says whether the desk is open", () => {
  const card = withoutComments(readFileSync(CARD, "utf8"));
  expect(card).toContain('"mt-4 grid gap-2.5 max-sm:hidden sm:grid-cols-2 lg:grid-cols-3"');
  expect(card).toContain('"mt-4 grid gap-2.5 sm:grid-cols-2 lg:grid-cols-3"');
  expect(card).not.toMatch(/"mt-4 grid gap-2\.5[^"]*(?<!max-)sm:hidden/);
  expect(card).toContain('<div className="mt-3 min-h-12 sm:hidden">');
  expect(card).toContain('<p className="flex min-h-12 items-center gap-2 text-sm text-ink-500">');
  expect(card).toContain('<ActionGroup fill className="mt-3 sm:hidden">');
  expect(card).toContain("aria-expanded={expanded}");
  expect(card).toContain('aria-controls="ministry-desk-screens"');
  expect(card).toContain('id="ministry-desk-screens"');
  expect(card).toContain('{expanded ? "Show less" : "Show all"}');
});

test("the reveal honours both reduced-motion switches and only plays when asked for", () => {
  const card = withoutComments(readFileSync(CARD, "utf8"));
  expect(card).toContain("useAppReducedMotion()");
  expect(card).not.toMatch(/\buseReducedMotion\(/);
  expect(card).toContain("staggerParent(reduce");
  expect(card).toContain("riseItem(reduce");
  expect(card).toContain('initial={reveal > 0 ? "hidden" : false}');
});

test("storage is read after mount and every access is wrapped", () => {
  const card = withoutComments(readFileSync(CARD, "utf8"));
  expect(card).toMatch(/useEffect\(\(\) => \{\s*let stored = false;\s*try \{\s*stored = readMinistryDeskExpanded\(window\.localStorage\)/);
  expect(card).toMatch(/try \{\s*writeMinistryDeskExpanded\(window\.localStorage, next\);/);
});
