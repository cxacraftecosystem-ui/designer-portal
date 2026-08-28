import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { OPEN_TASK_BADGE_HREF, OPEN_TASK_COUNT_PATH, openTaskBadgeSentence } from "@/components/tasks/openTaskCount";

/**
 * THE "YOU HAVE BEEN ASSIGNED WORK" BADGE — the request behind it, the sentence on it, and the two
 * places it has to be drawn.
 *
 * ── THE DEFECT THIS FILE EXISTS FOR ─────────────────────────────────────────────────────────────
 *
 * Every regression available to this feature is invisible in a screenshot of a working badge.
 *
 *   1. A badge that fetched a normal page would print the identical number while making the server
 *      resolve twenty tasks' workshop titles, artisan rows and questionnaire sections — and while
 *      `withDerived` defaults to TRUE on `/tasks`, so it would also run the corpus-side progress
 *      derivation, on every signed-in account, for a two-character pill. Nothing on screen says so.
 *   2. A badge drawn only in the desktop dropdown looks complete to whoever built it on a laptop and
 *      does not exist for a designer on a tablet — the sheet is the keyboard and touch route to
 *      every destination (frontend skill §7.8).
 *   3. A plural rule written inside JSX is exercised only by somebody looking at a screen with
 *      exactly one open task on it.
 *
 * ── WHY PART PURE CALL AND PART SOURCE READ ─────────────────────────────────────────────────────
 *
 * The split `dropdown-sweep-unit.spec.ts` and `dashboard-tile-parity-unit.spec.ts` already make, for
 * their reason. The path and the sentence are pure and are tested by CALLING them, which is what
 * `components/tasks/openTaskCount.ts` was extracted for. Whether the nav renders the badge at both
 * sites, and whether the fetch is tied to the entry being visible, lives inside a React component
 * and this repository has no React renderer in its devDependencies — Playwright is the whole of it —
 * so those are read out of the source.
 *
 * WHAT THE SOURCE READS DO NOT PROVE: that a browser paints or announces any of it, or that the
 * count is right. The count's correctness is the backend's (`/tasks` `total`), and a signed-in spec
 * asserting a number would need an account with a known, fixed set of open tasks.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

test.describe("open-task badge — the request", () => {
  test("asks for ONE row: a page of tasks would be built and thrown away", () => {
    expect(OPEN_TASK_COUNT_PATH).toContain("pageSize=1");
  });

  test("turns the derived progress off: a badge does not need it and it costs a corpus read per task", () => {
    // `/tasks` declares `withDerived: bool = Query(True)`, so OMITTING it is not neutral — the
    // expensive branch is the default. The value travels as a string because `buildQuery` takes no
    // booleans.
    expect(OPEN_TASK_COUNT_PATH).toContain("withDerived=false");
  });

  test("is the caller's own OPEN tasks, and nobody else's", () => {
    // view=assigned is hard-pinned to the caller server-side; naming it here keeps the count the
    // same list /tasks opens on rather than an admin's cross-repository view.
    expect(OPEN_TASK_COUNT_PATH).toContain("view=assigned");
    expect(OPEN_TASK_COUNT_PATH).toContain("status=OPEN");
    expect(OPEN_TASK_COUNT_PATH.startsWith("/tasks?")).toBe(true);
  });

  test("carries no other parameter — every one of them is a cost the badge cannot see", () => {
    const query = new URLSearchParams(OPEN_TASK_COUNT_PATH.slice(OPEN_TASK_COUNT_PATH.indexOf("?") + 1));
    expect([...query.keys()].sort()).toEqual(["pageSize", "status", "view", "withDerived"]);
  });
});

test.describe("open-task badge — the sentence", () => {
  test("says what it counts, never a bare digit", () => {
    expect(openTaskBadgeSentence(3)).toBe("3 open tasks are assigned to you");
  });

  test("agrees with itself at one", () => {
    expect(openTaskBadgeSentence(1)).toBe("1 open task is assigned to you");
  });

  test("the plural rule is the count's, not a fixed suffix", () => {
    // 0 never reaches a reader — the badge returns null below 1 — but the rule must not read "1" as
    // "anything that is not many", which is how "0 open task" gets printed.
    expect(openTaskBadgeSentence(0)).toBe("0 open tasks are assigned to you");
    expect(openTaskBadgeSentence(12)).toBe("12 open tasks are assigned to you");
  });
});

test.describe("open-task badge — where it is drawn", () => {
  const nav = read("components/DynamicIslandNav.tsx");

  test("rides on the Tasks entry", () => {
    expect(OPEN_TASK_BADGE_HREF).toBe("/tasks");
    expect(nav).toContain(`{ href: "/tasks", label: "Tasks"`);
  });

  test("is rendered TWICE — the desktop dropdown and the sheet", () => {
    // A notification that only exists on a pointer-driven hover menu does not reach a designer on a
    // tablet, and the sheet is the keyboard route to every destination.
    const sites = nav.match(/<OpenTaskBadge count=\{openTaskCount\} \/>/g) ?? [];
    expect(sites).toHaveLength(2);
  });

  test("every site tests the SAME href constant, never a literal", () => {
    // Three, and the third is the point: the two render guards plus the `tasksEntryVisible` test
    // that decides whether to fetch at all. A literal in any one of them is how the badge and the
    // request that feeds it come to be about different destinations.
    const guards = nav.match(/item\.href === OPEN_TASK_BADGE_HREF/g) ?? [];
    expect(guards).toHaveLength(3);
    expect(nav).not.toContain('item.href === "/tasks"');
  });

  test("the fetch is tied to the entry being on screen, not hardcoded on", () => {
    // A nav entry is not a guard, but it IS the honest answer to "is this badge being drawn"; if the
    // destination is ever narrowed the request narrows with it, in one expression.
    expect(nav).toContain(
      'const tasksEntryVisible = visibleItems.some((item) => item.href === OPEN_TASK_BADGE_HREF);'
    );
    expect(nav).toContain("useOpenTaskCount(tasksEntryVisible)");
  });

  test("no permission was invented for it", () => {
    // `/tasks` is `can: everyone` because view=assigned asks for nothing but a login. A predicate
    // here would be a client-side rule with no backend dependency to mirror.
    expect(nav).not.toMatch(/useOpenTaskCount\([^)]*can[A-Z]/);
  });
});

test.describe("open-task badge — one poller, shared", () => {
  const hook = read("components/hooks/useOpenTaskCount.ts");

  test("there is one module-level cache and one in-flight request", () => {
    // Two badges in one component answering different numbers is the defect the shared store exists
    // to prevent — see `usePendingAccessCount`, which this is modelled on.
    expect(hook).toContain("let cached: number | null = null;");
    expect(hook).toContain("let inFlight: Promise<void> | null = null;");
    expect(hook).toContain("if (inFlight) return inFlight;");
    expect(hook).toContain("const subscribers = new Set<Subscriber>();");
  });

  test("a failed count is silent — it leaves the badge absent, not broken", () => {
    expect(hook).toContain(".catch(() => {");
  });

  test("no timer: it refreshes on focus and on a stale mount", () => {
    // A background poll on every page would be paid by every signed-in account, because unlike the
    // access queue this endpoint is open to all of them.
    expect(hook).not.toContain("setInterval");
    expect(hook).toContain('window.addEventListener("focus", onFocus);');
    expect(hook).toContain('window.removeEventListener("focus", onFocus);');
  });

  test("the tasks page corrects it after a status change", () => {
    // Moving a card to In progress takes it out of the OPEN count; the pill and the row are on the
    // same screen, so the badge must not go on contradicting the page it points at.
    const page = read("app/(protected)/tasks/page.tsx");
    expect(page).toContain("refreshOpenTaskCount()");
  });
});
