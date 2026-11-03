import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { OPEN_TASK_BADGE_HREF, OPEN_TASK_COUNT_PATH, openTaskBadgeSentence } from "@/components/tasks/openTaskCount";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

test.describe("open-task badge — the request", () => {
  test("asks for ONE row: a page of tasks would be built and thrown away", () => {
    expect(OPEN_TASK_COUNT_PATH).toContain("pageSize=1");
  });

  test("turns the derived progress off: a badge does not need it and it costs a corpus read per task", () => {
    expect(OPEN_TASK_COUNT_PATH).toContain("withDerived=false");
  });

  test("is the caller's own OPEN tasks, and nobody else's", () => {
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
    const sites = nav.match(/<OpenTaskBadge count=\{openTaskCount\} \/>/g) ?? [];
    expect(sites).toHaveLength(2);
  });

  test("every site tests the SAME href constant, never a literal", () => {
    const guards = nav.match(/item\.href === OPEN_TASK_BADGE_HREF/g) ?? [];
    expect(guards).toHaveLength(3);
    expect(nav).not.toContain('item.href === "/tasks"');
  });

  test("the fetch is tied to the entry being on screen, not hardcoded on", () => {
    expect(nav).toContain(
      'const tasksEntryVisible = visibleItems.some((item) => item.href === OPEN_TASK_BADGE_HREF);'
    );
    expect(nav).toContain("useOpenTaskCount(tasksEntryVisible)");
  });

  test("no permission was invented for it", () => {
    expect(nav).not.toMatch(/useOpenTaskCount\([^)]*can[A-Z]/);
  });
});

test.describe("open-task badge — one poller, shared", () => {
  const hook = read("components/hooks/useOpenTaskCount.ts");

  test("there is one module-level cache and one in-flight request", () => {
    expect(hook).toContain("let cached: number | null = null;");
    expect(hook).toContain("let inFlight: Promise<void> | null = null;");
    expect(hook).toContain("if (inFlight) return inFlight;");
    expect(hook).toContain("const subscribers = new Set<Subscriber>();");
  });

  test("a failed count is silent — it leaves the badge absent, not broken", () => {
    expect(hook).toContain(".catch(() => {");
  });

  test("no timer: it refreshes on focus and on a stale mount", () => {
    expect(hook).not.toContain("setInterval");
    expect(hook).toContain('window.addEventListener("focus", onFocus);');
    expect(hook).toContain('window.removeEventListener("focus", onFocus);');
  });

  test("the tasks page corrects it after a status change", () => {
    const page = read("app/(protected)/tasks/page.tsx");
    expect(page).toContain("refreshOpenTaskCount()");
  });
});
