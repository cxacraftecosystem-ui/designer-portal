import { expect, test } from "@playwright/test";

import {
  adoptionRateText,
  isWithheld,
  type AdoptionGroup
} from "@/lib/workshopAnalytics";

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

test("a withheld rate is an em dash and is never rendered as a percentage", () => {
  const withheld = group({ rate: null, observations: 3, adopted: 2, workshops: 2 });

  expect(adoptionRateText(withheld)).toBe("—");
  expect(adoptionRateText(withheld)).not.toBe("0%");
  expect(adoptionRateText(withheld)).not.toContain("%");
  expect(adoptionRateText(withheld)).not.toContain("NaN");
});

test("a withheld rate is flagged as withheld even when the counts beside it are populated", () => {
  const withheld = group({ rate: null, observations: 3, adopted: 2, workshops: 2 });

  expect(isWithheld(withheld)).toBe(true);
  expect(adoptionRateText(withheld)).toBe("—");
});

test("a measured rate is a whole percentage", () => {
  expect(adoptionRateText(group({ rate: 0.4167 }))).toBe("42%");
  expect(adoptionRateText(group({ rate: 1 }))).toBe("100%");
  expect(isWithheld(group({ rate: 0.4167 }))).toBe(false);
});

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
  for (const rate of [null, 0, 0.5, 1]) {
    const row = group({ rate });
    expect(isWithheld(row)).toBe(adoptionRateText(row) === "—");
  }
});
