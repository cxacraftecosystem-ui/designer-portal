import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

const PAGES: Array<{ name: string; path: string[]; from: string; to: string }> = [
  {
    name: "/products",
    path: ["app", "(protected)", "products", "page.tsx"],
    from: "async function load()",
    to: "if (!funnelReady) return;"
  },
  {
    name: "/tools",
    path: ["app", "(protected)", "tools", "page.tsx"],
    from: "async function load()",
    to: "if (!funnelReady) return;"
  },
  {
    name: "/processes",
    path: ["app", "(protected)", "processes", "page.tsx"],
    from: "async function load(pageToLoad = page)",
    to: "if (!funnelReady) return;"
  },
  {
    name: "/media",
    path: ["app", "(protected)", "media", "page.tsx"],
    from: "const load = useCallback(",
    to: "if (!placeRestored || !filtersReady) return;"
  },
  {
    name: "/ministry-dashboard",
    path: ["app", "(protected)", "ministry-dashboard", "page.tsx"],
    from: "const generation = (currentLoad.current += 1);",
    to: "if (!allowed) return;"
  }
];

for (const subject of PAGES) {
  test(`${subject.name} ignores a fetch that a newer one has already replaced`, () => {
    const body = between(read(...subject.path), subject.from, subject.to);

    expect(body, `${subject.name}: load() must stamp itself with a generation`).toContain(
      "const generation = (currentLoad.current += 1);"
    );

    const guards = body.match(/if \(generation !== currentLoad\.current\) return;/g) ?? [];
    expect(guards.length, `${subject.name}: guard every setData and setError, not just the first`).toBeGreaterThanOrEqual(2);

    expect(body, `${subject.name}: hold the result so it can be discarded`).not.toContain("setData(await");
  });
}

test("the convention this was copied from is still the convention", () => {
  const body = between(
    read("app", "(protected)", "artisans", "page.tsx"),
    "async function load()",
    "if (!funnelReady) return;"
  );
  expect(body).toContain("const generation = (currentLoad.current += 1);");
  expect(body).toContain("if (generation !== currentLoad.current) return;");
});
