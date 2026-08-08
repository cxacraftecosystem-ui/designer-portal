import { expect, test } from "@playwright/test";

import {
  adoptionRateText,
  isWithheld,
  type AdoptionGroup
} from "@/lib/workshopAnalytics";

/**
 * `lib/workshopAnalytics.ts` on its own — no browser, no server, no API.
 *
 * WHY THIS FILE EXISTS AT ALL, when the arithmetic it guards lives on the server and is already
 * pinned by `backend/tests/test_workshop_analytics.py`. The server withholds an adoption rate below
 * its floors and sends `rate: null`, meaning "we cannot say". The browser then has to render that,
 * and there is exactly one way to get it wrong: show it as 0%. Which reports a TOTAL FAILURE of the
 * scheme in precisely the clusters that have simply not been followed up yet — the loudest possible
 * lie, told about the most cautious data, in a document a ministry reads.
 *
 * THE COMPILER CANNOT CATCH IT, which is the whole reason for a test rather than a type. `null`
 * coerces to 0 through arithmetic and through `??`, so every one of these is valid TypeScript that
 * type-checks clean and ships the bug:
 *
 *     `${Math.round((group.rate ?? 0) * 100)}%`     -> "0%"
 *     `${Math.round(group.rate! * 100)}%`           -> "0%"
 *     group.rate ? pct(group.rate) : "0%"           -> "0%"
 *
 * `npx tsc --noEmit` is silent on all three. This spec is not.
 *
 * THE CASE THAT MAKES IT A REAL TEST rather than a null check is the PAIR at the bottom: a cluster
 * that was measured and genuinely adopted nothing (`rate: 0`) and a cluster whose rate was withheld
 * (`rate: null`) must not render alike. Asserting only that null gives an em dash would pass against
 * a function that returned an em dash for both, which loses a real finding instead of inventing one
 * — quieter than the bug above, and still wrong.
 *
 * A node spec, not a browser one, for the same reason `design-workshop-search-unit.spec.ts` is: the
 * module is pure, Playwright resolves the `@/` alias in the node process, and these run in
 * milliseconds against no dev server.
 */

/**
 * A group with everything the renderers read. Written as a factory rather than a literal per test so
 * that a field added to `AdoptionGroup` breaks one line here instead of nine.
 */
function group(over: Partial<AdoptionGroup> = {}): AdoptionGroup {
  return {
    dimension: "CLUSTER",
    label: "Bargarh",
    workshops: 6,
    observations: 12,
    adopted: 5,
    trial: 3,
    notAdopted: 4,
    unknown: 0,
    rate: 0.4167,
    largestWorkshopShare: 0.25,
    message: "a sentence the server wrote",
    ...over
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The refusal
 * ──────────────────────────────────────────────────────────────────────────── */

test("a withheld rate is an em dash and is never rendered as a percentage", () => {
  const withheld = group({ rate: null, observations: 3, adopted: 2, workshops: 2 });

  expect(adoptionRateText(withheld)).toBe("—");
  // The three shapes a `?? 0` or `!` regression would produce, named so a failure says which.
  expect(adoptionRateText(withheld)).not.toBe("0%");
  expect(adoptionRateText(withheld)).not.toContain("%");
  expect(adoptionRateText(withheld)).not.toContain("NaN");
});

test("a withheld rate is flagged as withheld even when the counts beside it are populated", () => {
  // The dangerous row: 2 of 3 adopted is a real numerator over a real denominator, and "67%" is what
  // arithmetic alone would print. The server refused it; the browser must not reinstate it.
  const withheld = group({ rate: null, observations: 3, adopted: 2, workshops: 2 });

  expect(isWithheld(withheld)).toBe(true);
  expect(adoptionRateText(withheld)).toBe("—");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The measurement
 * ──────────────────────────────────────────────────────────────────────────── */

test("a measured rate is a whole percentage", () => {
  expect(adoptionRateText(group({ rate: 0.4167 }))).toBe("42%");
  expect(adoptionRateText(group({ rate: 1 }))).toBe("100%");
  expect(isWithheld(group({ rate: 0.4167 }))).toBe(false);
});

/**
 * THE PAIR. Both of these are honest and they are opposite findings, so they must not look alike.
 *
 * `rate: 0` — somebody went back, asked about every design, and none had been taken up. That is a
 * measurement and it must show as 0%.
 * `rate: null` — too few workshops or too few outcomes to say anything at all.
 *
 * A renderer that collapses either into the other destroys a finding. Asserting them together is
 * what stops a "fix" for one direction from silently creating the other.
 */
test("a measured zero and a withheld rate are different findings and render differently", () => {
  const measuredZero = group({ rate: 0, observations: 14, adopted: 0, workshops: 6 });
  const refused = group({ rate: null, observations: 3, adopted: 0, workshops: 2 });

  expect(adoptionRateText(measuredZero)).toBe("0%");
  expect(adoptionRateText(refused)).toBe("—");
  expect(adoptionRateText(measuredZero)).not.toBe(adoptionRateText(refused));

  expect(isWithheld(measuredZero)).toBe(false);
  expect(isWithheld(refused)).toBe(true);
});

test("the two helpers cannot disagree about the same group", () => {
  // The page dims the ink using `isWithheld` and prints the text using `adoptionRateText`. If those
  // two ever disagree, a withheld figure is rendered in measured ink — or the reverse — and the one
  // visual cue that distinguishes them at a glance is lost.
  for (const rate of [null, 0, 0.5, 1]) {
    const row = group({ rate });
    expect(isWithheld(row)).toBe(adoptionRateText(row) === "—");
  }
});
