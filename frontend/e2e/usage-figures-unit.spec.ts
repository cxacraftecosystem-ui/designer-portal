import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { axisTop, labelStride, niceStep, statedRuns, tickText } from "../components/settings/usageChartMath";
import {
  bucketTickText,
  consentBasisText,
  consentMoment,
  errorRateText,
  isWithheldRoute,
  usageConsentGateOf
} from "../lib/usage";

test.describe("a gap in the data is a gap in the line", () => {
  test("a withheld bucket ENDS a run — the line is never drawn across it", () => {
    const runs = statedRuns([
      { value: 10, withheld: false },
      { value: 12, withheld: false },
      { value: null, withheld: true },
      { value: 14, withheld: false }
    ]);
    expect(runs).toEqual([[0, 1], [3]]);
  });

  test("a null with withheld:false ends a run too — 'no rate to compute' is also not a value", () => {
    expect(statedRuns([{ value: 0.1, withheld: false }, { value: null, withheld: false }, { value: 0.2, withheld: false }])).toEqual([
      [0],
      [2]
    ]);
  });

  test("a MEASURED ZERO stays in the run — it is a fact, not an absence", () => {
    expect(statedRuns([{ value: 5, withheld: false }, { value: 0, withheld: false }, { value: 7, withheld: false }])).toEqual([
      [0, 1, 2]
    ]);
  });

  test("a wholly withheld series produces no path at all, rather than a flat line on zero", () => {
    expect(statedRuns([{ value: null, withheld: true }, { value: null, withheld: true }])).toEqual([]);
  });

  test("runs at the very start and the very end are both closed", () => {
    expect(
      statedRuns([
        { value: null, withheld: true },
        { value: 1, withheld: false },
        { value: null, withheld: true },
        { value: 2, withheld: false }
      ])
    ).toEqual([[1], [3]]);
  });
});

test.describe("the value axis", () => {
  test("always includes zero — a series clustered at 900-1000 still starts at 0", () => {
    const [top, step] = axisTop([900, 950, 1000]);
    expect(top).toBeGreaterThanOrEqual(1000);
    expect(top % step).toBe(0);
  });

  test("NULLS ARE DROPPED, NOT READ AS ZERO", () => {
    expect(axisTop([1000, 2000, 4000])).toEqual(axisTop([1000, null, 2000, null, 4000]));
  });

  test("an all-null series gives a drawable axis rather than dividing by zero", () => {
    expect(axisTop([null, null])).toEqual([1, 1]);
  });

  test("an all-zero series gives a drawable axis too — a flat baseline is the truthful picture", () => {
    expect(axisTop([0, 0, 0])).toEqual([1, 1]);
  });

  test("steps are round: 1, 2, 2.5 or 5 times a power of ten, never 7", () => {
    for (const span of [3, 9, 37, 140, 999, 12345]) {
      const step = niceStep(span, 4);
      const mantissa = step / 10 ** Math.floor(Math.log10(step));
      expect([1, 2, 2.5, 5, 10]).toContain(Number(mantissa.toFixed(4)));
    }
  });

  test("tick text carries its unit and rounds to something a person reads", () => {
    expect(tickText(1234, "count")).toBe("1,234");
    expect(tickText(0.05, "percent")).toBe("5.0%");
    expect(tickText(0.5, "percent")).toBe("50%");
    expect(tickText(1234.6, "ms")).toBe("1,235 ms");
  });

  test("x labels are thinned to at most a dozen, so 168 hourly ticks do not overprint", () => {
    expect(labelStride(7)).toBe(1);
    expect(labelStride(168)).toBe(14);
    expect(168 / labelStride(168)).toBeLessThanOrEqual(12);
  });
});

test.describe("a refused figure never renders as a number", () => {
  test("errorRateText(null) is a dash — NOT '0.0%'", () => {
    expect(errorRateText(null)).toBe("—");
  });

  test("errorRateText(0) IS '0.0%' — a measured zero is a real answer", () => {
    expect(errorRateText(0)).toBe("0.0%");
  });

  test("a small but non-zero rate is not rounded away to 0.0%", () => {
    expect(errorRateText(0.0004)).toBe("0.04%");
  });

  test("isWithheldRoute reads the flag and nothing else, on every row shape the API sends", () => {
    expect(isWithheldRoute({ withheld: true })).toBe(true);
    expect(isWithheldRoute({ withheld: false })).toBe(false);
  });

  test("bucket ticks stay in UTC — converting them here would silently re-bucket the data", () => {
    expect(bucketTickText("2026-08-30T17:00:00+00:00", "hour")).toBe("17:00");
    expect(bucketTickText("2026-08-30T00:00:00+00:00", "day")).toBe("30/8");
  });
});

test.describe("the consent gate is read, never derived", () => {
  test("a payload with no gate yields null — 'do not claim to know', not 'nothing needed'", () => {
    expect(usageConsentGateOf({ id: "u1", email: "a@b.c" })).toBeNull();
    expect(usageConsentGateOf(null)).toBeNull();
    expect(usageConsentGateOf(undefined)).toBeNull();
  });

  test("a malformed gate is treated as absent rather than as permissive", () => {
    expect(usageConsentGateOf({ usageConsentGate: { required: "yes", reason: "x" } })).toBeNull();
    expect(usageConsentGateOf({ usageConsentGate: { required: true } })).toBeNull();
    expect(usageConsentGateOf({ usageConsentGate: "required" })).toBeNull();
  });

  test("a well-formed gate comes back whole, including the sentence the client renders", () => {
    const gate = usageConsentGateOf({
      usageConsentGate: { state: "REFUSED", required: false, reason: "They declined.", noticeVersion: "2026-08-30.1" }
    });
    expect(gate?.required).toBe(false);
    expect(gate?.reason).toBe("They declined.");
  });

  test("the circumstance is rendered in words, and an unknown token is passed through, not guessed", () => {
    expect(consentBasisText("REQUIRED_AT_SIGN_IN")).toBe("Required at sign-in");
    expect(consentBasisText("OFFERED_IN_SETTINGS")).toBe("Chosen in Settings");
    expect(consentBasisText("SOMETHING_NEWER")).toBe("SOMETHING_NEWER");
    expect(consentBasisText(null)).toBe("Not recorded");
  });

  test("an absent date is a dash, never today's", () => {
    expect(consentMoment(null)).toBe("—");
    expect(consentMoment(undefined)).toBe("—");
  });
});

test("the notice's sentences live on the server, and are nowhere in this client", () => {
  const roots = ["app", "components", "lib"];
  const banned = [
    "You need to agree to this to sign in",
    "Kept until you withdraw or your account is deleted",
    "Withdrawing does not sign you out",
    "You have declined, so nothing about how you use the platform is recorded"
  ];

  const offenders: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
        continue;
      }
      if (!/\.(ts|tsx)$/.test(entry.name)) continue;
      const source = readFileSync(full, "utf8");
      for (const phrase of banned) {
        if (source.includes(phrase)) offenders.push(`${full} :: ${phrase}`);
      }
    }
  };
  for (const root of roots) walk(root);

  expect(offenders, "the server owns this copy — render it, do not restate it").toEqual([]);
});
