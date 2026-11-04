import { expect, test } from "@playwright/test";

import { describe, pySum } from "@/lib/marketAnalysis";

function naiveSum(values: number[]): number {
  let total = 0;
  for (const value of values) total += value;
  return total;
}

test("an empty sum is zero, and an ordinary one is not silently made exact", () => {
  expect(pySum([])).toBe(0);
  expect(pySum([0.1, 0.2])).toBe(0.30000000000000004);
});

test("the small terms survive extremes that cancel", () => {
  const orphans = [1560, 1e308, 1000, -1e308];
  expect(pySum(orphans)).toBe(2560);
  expect(naiveSum(orphans)).toBe(0);

  const material = [624.5, -1e308, 125000, 0.01, 1e308, 7];
  expect(pySum(material)).toBe(125631.51);
  expect(naiveSum(material)).toBe(7);
});

test("an overflow stays infinite and never becomes a NaN", () => {
  expect(pySum([1e308, 1e308])).toBe(Number.POSITIVE_INFINITY);

  expect(pySum([-1e308, -1e308, 1e308, 1e308])).toBe(Number.NEGATIVE_INFINITY);
});

test("the mean of a sample holding both ends of the range is the server's", () => {
  const distribution = describe([1e308, -1e308, 600, 400]);
  expect(distribution).not.toBeNull();
  expect(distribution!.mean).toBe(250);
  expect(distribution!.count).toBe(4);
  expect(distribution!.quantilesReported).toBe(false);

  expect(naiveSum([-1e308, 400, 600, 1e308]) / 4).toBe(0);
});
