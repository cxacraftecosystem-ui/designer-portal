import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE LATE ANSWER THAT LANDS LAST, WINS, AND KILLS THE NEXT BUTTON.
 *
 * Four list pages — /products, /tools, /processes and /media — called `setData(await
 * listResource(...))` in RESOLUTION order with no generation counter anywhere in the file, while
 * /artisans, /questionnaires, /design-workshops and /questionnaire had all carried one for the same
 * reason for as long as they have existed. None of these pages debounces the REQUEST: products,
 * tools and processes debounce only `setApplied`, and media's `clearTimeout` cancels a load that has
 * not fired yet and does nothing whatever to one already in flight. Two requests overlapping is
 * therefore ordinary, not exotic.
 *
 * WHY IT IS WORSE THAN A STALE TABLE. `<Pagination>` on all four pages is rendered with
 * `page={data.page}` — the ANSWERED page — while the refetch effect depends on `page`, the
 * REQUESTED one. Type a search (request A, page 1), press Next before it lands (request B, page 2),
 * let B answer first and A answer second: the table is page 1's rows, `data.page` is 1, and `page`
 * state is 2. Next now computes `onPage(data.page + 1)` = `setPage(2)` — the value already held —
 * React bails on the identical scalar, no dependency in `[funnelReady, page, applied, funnel]`
 * changes, no request is issued, and the button is dead until Previous is pressed. Audit 2026-08-15
 * (MINOR, frontend) filed exactly this, and counted four pages where the original finder counted
 * three.
 *
 * WHY THIS IS A SOURCE READ. The fetch and its `setData` live inline inside a client page component,
 * and this repository has no React renderer in its devDependencies — Playwright is the whole of it —
 * so mounting the page is not available at all. `questionnaire-workshop-filter-unit.spec.ts`,
 * `discarded-work-unit.spec.ts` and `derived-fields-unit.spec.ts` read their subjects the same way
 * and for the same reason. What this proves is that the guard is present on every write inside every
 * one of the four `load()` bodies; what it cannot prove is that a browser repaints correctly, which
 * belongs in a signed-in spec against a throttled connection if one is ever written.
 *
 * All four assertions below fail against the files as they were: a grep for `currentLoad|generation`
 * returned nothing in any of them.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

/** The text between two markers, so an assertion cannot drift into a neighbouring function. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

/**
 * The four pages, each with the markers that bracket its own `load()`. The end marker is the comment
 * that follows the function in each file rather than a closing brace, because a brace count is
 * exactly the kind of assertion that silently starts matching the wrong function after a refactor.
 */
const PAGES: Array<{ name: string; path: string[]; from: string; to: string }> = [
  {
    name: "/products",
    path: ["app", "(protected)", "products", "page.tsx"],
    from: "async function load()",
    to: "// Waits for the funnel's initial onChange"
  },
  {
    name: "/tools",
    path: ["app", "(protected)", "tools", "page.tsx"],
    from: "async function load()",
    to: "// Waits for the funnel's initial onChange"
  },
  {
    name: "/processes",
    path: ["app", "(protected)", "processes", "page.tsx"],
    from: "async function load(pageToLoad = page)",
    to: "// Waits for the funnel's initial onChange"
  },
  {
    name: "/media",
    path: ["app", "(protected)", "media", "page.tsx"],
    from: "const load = useCallback(",
    to: "// Live search: debounce keystrokes"
  }
];

for (const subject of PAGES) {
  test(`${subject.name} ignores a fetch that a newer one has already replaced`, () => {
    const body = between(read(...subject.path), subject.from, subject.to);

    // 1. The generation is taken BEFORE the await, as the first statement of the load. Taken after,
    //    it would be the newest generation by definition and the guard would never fire.
    expect(body, `${subject.name}: load() must stamp itself with a generation`).toContain(
      "const generation = (currentLoad.current += 1);"
    );

    // 2. Every write is guarded. Counted rather than merely "contains", because the defect this
    //    spec closes is one unguarded `setData` — a load with the counter at the top and no check
    //    at the bottom is the original bug with ceremony attached. Two writes exist in each body:
    //    the success `setData`/`setError(null)` pair and the `catch`'s `setError`. A stale FAILURE
    //    matters as much as a stale success: it paints an error over rows that loaded fine.
    const guards = body.match(/if \(generation !== currentLoad\.current\) return;/g) ?? [];
    expect(guards.length, `${subject.name}: guard every setData and setError, not just the first`).toBeGreaterThanOrEqual(2);

    // 3. The await must be assigned, not passed straight into `setData(...)`. `setData(await …)`
    //    cannot be guarded at all — that inline shape is precisely what all four files had.
    expect(body, `${subject.name}: hold the result so it can be discarded`).not.toContain("setData(await");
  });
}

/**
 * The control. /artisans is the page the four were copied FROM, and it is untouched by this change;
 * if this assertion ever fails, the convention itself has moved and the four above should follow it
 * rather than be left as the last holders of a retired pattern.
 */
test("the convention this was copied from is still the convention", () => {
  const body = between(
    read("app", "(protected)", "artisans", "page.tsx"),
    "async function load()",
    "// Waits for the funnel's initial onChange"
  );
  expect(body).toContain("const generation = (currentLoad.current += 1);");
  expect(body).toContain("if (generation !== currentLoad.current) return;");
});
