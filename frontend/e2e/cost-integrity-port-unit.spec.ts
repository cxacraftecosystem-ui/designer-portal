import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { analyseCostIntegrity, unsyncedSheetCount, type CostFindingsPayload, type CostRow } from "@/lib/costIntegrity";

/**
 * `lib/costIntegrity.ts` PROVED EQUAL TO `backend/app/services/cost_integrity.py`, case for case.
 *
 * ── WHY THIS SPEC IS THE WHOLE VALUE OF THE PORT ──────────────────────────────────────────────
 * The panel this feeds tells a designer that the material lines under a cost sheet add up to
 * ₹1,650.00 while the header declares ₹1,560.00 — and the header is what the report prints into a
 * document submitted to a Development Commissioner's office. A port that is merely ROUGHLY the same
 * arithmetic is worse than no port: it would contradict the handset and the server about the same
 * workshop, and the designer would have no way to tell which of the three was lying.
 *
 * ── THE EXPECTATIONS ARE NOT WRITTEN HERE ─────────────────────────────────────────────────────
 * `android/app/src/test/resources/dw-analysis-cases.json` and `dw-analysis-expected.json` are the
 * SHARED case table and the goldens REGENERATED FROM THE BACKEND MODULES THEMSELVES — the same two
 * files `DwAnalysisParityTest.kt` reads. So this spec compares the browser against CPython's own
 * output rather than against a fixture somebody wrote from what this file already did, which would
 * prove only that it still does it. Its 28 cost cases were built to be adversarial, and the case
 * table's own note says so: "Do not 'tidy' a value: the odd spellings (a hexadecimal scrap, Odia
 * digits, a signed zero, a no-break space) are each a place the two languages part company."
 *
 * The comparison is on the WHOLE payload — every message string, every rounded figure, the caution
 * and warning lists and their order. Nothing is spot-checked, so a divergence cannot hide in a key
 * this spec forgot to name.
 *
 * ── THE FIVE PLACES THE TWO LANGUAGES ACTUALLY PART COMPANY ───────────────────────────────────
 * Named because each is a real bug someone will otherwise reintroduce while "simplifying":
 *
 *  1. **`sum()` is compensated; `+=` is not.** Since CPython 3.12 the builtin is Neumaier's
 *     algorithm. `cost_integrity` uses `+=` for the roll-ups and the cost heads and `sum()` for the
 *     orphan total, so the port must use a naive add for the first two and `pySum` for the third —
 *     `k27` fails if either is spelled the other way.
 *  2. **Rounding ties.** Python rounds half to EVEN; `toFixed` rounds half away from zero. `k17`
 *     lands a margin exactly on a tie.
 *  3. **Non-finite spellings.** Python prints `inf`, `-inf`, `nan`; JavaScript prints `Infinity`.
 *     `k28` is a caution that overflows.
 *  4. **Reading a stored MONEY value.** A fixed-2 string with grouping commas, a no-break space, a
 *     hexadecimal scrap — `k25` and the shared `asNumber` cover these.
 *  5. **Dict ordering.** Orphans keep the order the lines were entered in, which `k19` and `k27`
 *     both depend on; a `Map`, not an object, is what preserves it in the browser.
 */

/**
 * The Android test resources, which are the canonical home of the shared table.
 *
 * READ ACROSS THE REPOSITORY RATHER THAN COPIED, deliberately. A copy under `frontend/e2e/fixtures`
 * would be a second table to regenerate, and the day somebody regenerated one and not the other the
 * two clients would be proved equal to two different servers.
 */
const RESOURCES = join(__dirname, "..", "..", "android", "app", "src", "test", "resources");

type CostCase = {
  name: string;
  comment?: string;
  sheets: CostRow[];
  materialLines: CostRow[];
  labourLines: CostRow[];
  labels?: Record<string, string>;
};

const cases: CostCase[] = JSON.parse(readFileSync(join(RESOURCES, "dw-analysis-cases.json"), "utf8")).cost;
const expected: Record<string, CostFindingsPayload> = JSON.parse(
  readFileSync(join(RESOURCES, "dw-analysis-expected.json"), "utf8")
);

test("the shared case table is present and is the one the handset reads", () => {
  // A spec that silently ran zero cases would be a green tick over an unproved port. The count is
  // asserted rather than the mere presence of the file, so a table trimmed by half is a failure.
  expect(cases.length).toBeGreaterThanOrEqual(28);
  for (const one of cases) expect(expected[`cost/${one.name}`], `no golden for ${one.name}`).toBeTruthy();
});

for (const one of cases) {
  test(`cost/${one.name} matches the backend byte for byte`, () => {
    const actual = analyseCostIntegrity({
      sheets: one.sheets,
      materialLines: one.materialLines,
      labourLines: one.labourLines,
      labels: one.labels ?? {}
    });
    /*
      `toEqual` over the WHOLE object: messages, figures, verdicts, list order and all.

      COMPARED DIRECTLY, NOT THROUGH `JSON.parse(JSON.stringify(actual))`. That round trip looks like
      a harmless normalisation and it destroys the one value this comparison most needs to keep:
      `JSON.stringify(-0)` is `"0"`. Python's `round(-0.0, 2)` is `-0.0` and the golden carries `-0`,
      which arrives as a real negative zero because it is PARSED rather than stringified — and
      `k26-live-workshop` has one, on a sheet whose seven material lines accumulate a few
      quadrillionths above the subtotal declared for them. Passing that comparison by flattening the
      sign would be the spec agreeing with a bug rather than catching it.
    */
    expect(actual).toEqual(expected[`cost/${one.name}`]);
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * The one rule that is the PANEL's rather than the port's
 * ──────────────────────────────────────────────────────────────────────────── */

test("a sheet with no server id yet is counted as unsynced, not as missing", () => {
  /*
    `costSheetRef` holds the sheet's SERVER id, and the picker that fills it is served from the
    references endpoint — so a sheet created in a courtyard cannot be offered to its own lines at
    all, the lines land in the orphan bucket, and the port's orphan caution offers "the sheet they
    named may have been deleted" as the explanation. On a screen where that sheet is three rows up
    waiting for a tower, that sentence tells a designer their morning's costing has been deleted.

    The count is what lets the panel withhold the caution without editing the server's sentence. A
    row whose `_entryId` is present but EMPTY counts as unsynced for the same reason the analysis
    refuses to match on it: an empty string is not an identity.
  */
  expect(unsyncedSheetCount([])).toBe(0);
  expect(unsyncedSheetCount([{ _entryId: "s1" }])).toBe(0);
  expect(unsyncedSheetCount([{ _entryId: "s1" }, {}, { _entryId: "" }])).toBe(2);
});
