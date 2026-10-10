import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { buildOfflineReport, type OfflineReportInput, type ReportCase } from "@/lib/offlineReport/assemble";
import type { Registry } from "@/lib/offlineReport/builder";
import { pyFixed, pyG, pyRound, splitByScript } from "@/lib/offlineReport/model";

/**
 * THE BROWSER'S REPORT IS THE SERVER'S REPORT, block for block, for a fixture workshop.
 *
 * `shared/report-parity/workshop.json` is one workshop rich enough to reach every branch the
 * builder has — a cover with more fields than its table holds, rich text with every mark, tables
 * and cards, iterations grouped under their prototypes, a REF to a deleted row, photographs over a
 * template's cap, attachments a file cannot carry, all seven figures, the map, Odia text, the
 * designer's own sections, questionnaire sittings, transcripts and machine-assisted text.
 * `backend/tools/report_offline_parity.py` built each case in it with the SERVER's code and wrote
 * `expected.json`; `backend/tests/test_report_offline_parity.py` fails if that file is stale. This
 * builds the same cases with `lib/offlineReport` and requires the same meta, theme, blocks and
 * warnings. A change to either builder that the other does not make fails one side or the other.
 *
 * The registry is the one the handset bundles (`design-workshop-schema.json`), which the backend's
 * own tests hold to `registry_to_dict()`.
 */

const ROOT = join(__dirname, "..", "..");
const fixture = JSON.parse(readFileSync(join(ROOT, "shared", "report-parity", "workshop.json"), "utf8")) as OfflineReportInput & {
  cases: Array<ReportCase & { name: string }>;
};
const expected = JSON.parse(readFileSync(join(ROOT, "shared", "report-parity", "expected.json"), "utf8")) as {
  registryStages: string[];
  cases: Record<string, { meta: Record<string, unknown>; theme: Record<string, unknown>; blocks: unknown[]; warnings: string[] }>;
};
const registry = JSON.parse(
  readFileSync(join(ROOT, "android", "app", "src", "main", "assets", "design-workshop-schema.json"), "utf8")
) as Registry;

test("the fixture is built against the registry the server built it against", () => {
  expect(registry.stages.map((s) => s.key)).toEqual(expected.registryStages);
});

for (const kase of fixture.cases) {
  test.describe(`case ${kase.name}`, () => {
    const want = expected.cases[kase.name];
    const built = buildOfflineReport(registry, fixture, kase);

    test("the document's meta and theme match", () => {
      expect(built.document.meta).toEqual(want.meta);
      expect(built.document.theme).toEqual(want.theme);
    });

    test("every block matches, in order", () => {
      // Compared one at a time so a failure names the first block that differs, not a 3,000-line diff.
      const got = JSON.parse(JSON.stringify(built.document.blocks)) as unknown[];
      for (let i = 0; i < Math.max(got.length, want.blocks.length); i += 1) {
        expect(got[i], `block ${i}`).toEqual(want.blocks[i]);
      }
    });

    test("the warnings match, word for word", () => {
      expect(built.warnings).toEqual(want.warnings);
    });
  });
}

test.describe("the Python formatting the report depends on", () => {
  test("round() is half to even", () => {
    expect([0.5, 1.5, 2.5, 3.5, -0.5].map(pyRound)).toEqual([0, 2, 2, 4, 0]);
  });

  test("a fixed-point format rounds the exact binary value half to even", () => {
    expect(pyFixed(0.125, 2)).toBe("0.12");
    expect(pyFixed(0.375, 2)).toBe("0.38");
    expect(pyFixed(1850.5, 2)).toBe("1850.50");
    expect(pyFixed(2.5, 0)).toBe("2");
    expect(pyFixed(-0.001, 2)).toBe("-0.00");
  });

  test("%g drops trailing zeros and switches to an exponent where Python does", () => {
    expect(pyG(4.5)).toBe("4.5");
    expect(pyG(16)).toBe("16");
    expect(pyG(1234567)).toBe("1.23457e+06");
    expect(pyG(0.0001)).toBe("0.0001");
    expect(pyG(0.00001)).toBe("1e-05");
  });

  test("a mixed line is split at every script boundary, punctuation joining the run before it", () => {
    expect(splitByScript("Sambalpuri Ikat (ସମ୍ବଲପୁରୀ ଇକତ) weave")).toEqual([
      ["Sambalpuri Ikat (", "LATIN"],
      ["ସମ୍ବଲପୁରୀ ଇକତ) ", "ODIA"],
      ["weave", "LATIN"]
    ]);
  });
});
