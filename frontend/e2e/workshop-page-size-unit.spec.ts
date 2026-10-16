import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { LIST_PAGE_CEILING } from "@/components/data/cappedList";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import { WORKSHOP_OPTION_PAGE_SIZE } from "@/lib/workshopOptions";

/**
 * EVERY `pageSize` SENT TO `GET /workshops` IS AT MOST 100 (UX audit DW-01, 2026-10-16).
 *
 * The route declares `pageSize: int = Query(20, ge=1, le=100)`, so a larger number is not clamped —
 * it is a 422. The "Design workshop visibility" panel asked for 200, was refused on every visit, and
 * told every admin the type filter was off. Nothing failed anywhere a developer would look: the
 * panel caught the refusal and printed a sentence. This test reads the source rather than the
 * screen, so the next literal over the ceiling fails here instead.
 */
const ROUTE_CEILING = 100;
const ROOT = join(__dirname, "..");
const KNOWN: Record<string, number> = {
  LIST_PAGE_CEILING,
  RENDER_CAP,
  WORKSHOP_OPTION_PAGE_SIZE
};

function sources(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) sources(path, out);
    else if (/\.(ts|tsx)$/.test(name)) out.push(path);
  }
  return out;
}

function resolve(token: string, text: string): number | null {
  if (/^\d+$/.test(token)) return Number(token);
  if (token in KNOWN) return KNOWN[token];
  const local = new RegExp(`const ${token}\\s*=\\s*(\\d+|[A-Z_]+)\\s*;`).exec(text);
  if (!local) return null;
  return resolve(local[1], text);
}

test("no caller asks GET /workshops for more than the route's ceiling", () => {
  const offenders: string[] = [];
  let calls = 0;
  for (const file of ["app", "components", "lib"].flatMap((dir) => sources(join(ROOT, dir)))) {
    const text = readFileSync(file, "utf8");
    const pattern = /"\/workshops"\s*,\s*\{([^}]*)\}/g;
    for (let match = pattern.exec(text); match; match = pattern.exec(text)) {
      const size = /pageSize:\s*([A-Za-z_0-9]+)/.exec(match[1]);
      if (!size) continue;
      calls += 1;
      const value = resolve(size[1], text);
      if (value === null || value > ROUTE_CEILING) {
        offenders.push(`${file.slice(ROOT.length + 1)}: pageSize ${size[1]} = ${value ?? "unresolved"}`);
      }
    }
  }
  expect(calls, "the scan found no /workshops callers at all — the pattern is stale").toBeGreaterThan(3);
  expect(offenders).toEqual([]);
});
