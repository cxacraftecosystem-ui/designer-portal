import { expect, test } from "@playwright/test";

import { describe, pySum } from "@/lib/marketAnalysis";

/**
 * `pySum` on its own — no browser, no server, no IndexedDB.
 *
 * WHY THIS EXISTS. Every figure stage 9 prints is summed, and the server sums with CPython's
 * `sum()`, which has not been a running total since 3.12 — it is the improved Kahan-Babuška
 * algorithm by Neumaier, and `backend/Dockerfile` pins `PYTHON_VERSION=3.12`. A `for` loop and
 * `reduce((a, b) => a + b)` are both the naive spelling, and both were what this module used. The
 * two agree until a sample mixes magnitudes far enough apart that the small values fall off the
 * bottom of the accumulator as they are added; if the extremes then cancel, the server still holds
 * the small ones and the running total has already lost them.
 *
 * IT WAS FOUND BY A DIFFERENTIAL FUZZ, not by reading. 280 generated cases run through the Kotlin
 * port and through the Python disagreed on three, all of them the same sentence, and the same
 * defect was then found here by inspection. The Kotlin side is pinned by
 * `m29-mean-compensated-sum`, `k27-orphan-total-compensated-sum` and `k28-orphan-total-overflows`
 * in `android/app/src/test/resources/dw-analysis-cases.json`, whose expectations are regenerated
 * from the backend modules themselves. These are the same numbers, asserted on this port.
 *
 * THE EXPECTATIONS ARE CPYTHON'S OWN OUTPUT, read off `sum()` under 3.12 semantics rather than
 * derived by hand — a golden written from what this file already did would prove only that it still
 * does it.
 */

/** What the module used to do, kept as a witness so each case shows it is discriminating. */
function naiveSum(values: number[]): number {
  let total = 0;
  for (const value of values) total += value;
  return total;
}

test("an empty sum is zero, and an ordinary one is not silently made exact", () => {
  expect(pySum([])).toBe(0);
  // NOT 0.3. `sum([0.1, 0.2])` is 0.30000000000000004 in Python too: the compensation recovers what
  // the ACCUMULATOR drops between terms, and it is not `math.fsum`, which would round differently
  // here and raises OverflowError on the cases below instead of answering inf.
  expect(pySum([0.1, 0.2])).toBe(0.30000000000000004);
});

test("the small terms survive extremes that cancel", () => {
  // The orphan-cost caution: `[1560, 1e308, 1000, -1e308]`. The server says these lines in no
  // subtotal come to ₹2,560.00; a running total says ₹0.00.
  const orphans = [1560, 1e308, 1000, -1e308];
  expect(pySum(orphans)).toBe(2560);
  expect(naiveSum(orphans)).toBe(0);

  // The case the fuzz actually failed on, where the naive answer is not merely wrong but plausible.
  const material = [624.5, -1e308, 125000, 0.01, 1e308, 7];
  expect(pySum(material)).toBe(125631.51);
  expect(naiveSum(material)).toBe(7);
});

test("an overflow stays infinite and never becomes a NaN", () => {
  // The compensation term for a step that overflows is itself infinite, so folding it back
  // unconditionally answers NaN — which would print "₹nan" against the server's "₹inf". This is the
  // case that constrains the FIX rather than the bug.
  expect(pySum([1e308, 1e308])).toBe(Number.POSITIVE_INFINITY);

  // Once the running total is -inf it stays there, and the two positive terms that follow do not
  // rescue it. CPython answers -inf for this, not NaN and not 0.
  expect(pySum([-1e308, -1e308, 1e308, 1e308])).toBe(Number.NEGATIVE_INFINITY);
});

test("the mean of a sample holding both ends of the range is the server's", () => {
  // `describe` sorts before it sums, so these arrive as [-1e308, 400, 600, 1e308] however they were
  // collected. Four prices, so the quantiles stay unreported and the mean is the whole of it.
  const distribution = describe([1e308, -1e308, 600, 400]);
  expect(distribution).not.toBeNull();
  expect(distribution!.mean).toBe(250);
  expect(distribution!.count).toBe(4);
  expect(distribution!.quantilesReported).toBe(false);

  // The witness: the same four prices through the accumulator this module used to have.
  expect(naiveSum([-1e308, 400, 600, 1e308]) / 4).toBe(0);
});
