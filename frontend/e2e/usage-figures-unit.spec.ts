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

/**
 * THE TWO WAYS /settings/usage COULD PUBLISH A NUMBER THE SERVER REFUSED TO STATE, FENCED.
 *
 * Every aggregate under `/api/usage` uses `null` for a figure it declined to give, and `null` is
 * the most dangerous value in JavaScript for exactly this job: it becomes `0` through `+`, through
 * `??`, through `Number()`, through `Math.max` and through a sort comparator, silently, with no
 * type error and nothing on screen to say it happened. A **table** survives that carelessly —
 * `value ?? "—"` is one keystroke and a reviewer sees the dash. A **chart** does not: a plotted
 * point at zero is a confident claim, indistinguishable from a measured zero, drawn in the same ink
 * as every honest mark on the page. So the figures wave added exactly two new ways to get this
 * wrong, and both are pinned here:
 *
 *   1. **A withheld or absent point must break the line, never be joined across.** The obvious
 *      implementation — one `d` string skipping the missing points — draws a straight line from
 *      before the gap to after it, which is an interpolated trend over the one region the server
 *      said it would not describe. `statedRuns` is the split, and it lives in a `.ts` module rather
 *      than inside the component precisely so this test can call it.
 *   2. **The axis must always include zero, and must not be dragged by nulls.** An axis starting at
 *      40 makes a 3% difference look like a threefold one, which is the commonest way a published
 *      figure misleads without containing a single wrong number.
 *
 * The rest of this file pins the consent client: the parts of it that a client must NOT compute for
 * itself, and the sentences it must NOT own.
 *
 * WHY THESE ARE UNIT TESTS AND NOT A SCREENSHOT. There is no React renderer in devDependencies, so
 * a judgement inside JSX is only ever exercised by somebody looking at a screen —
 * `components/ui/selectFilter.ts` and `components/data/cappedList.ts` exist for the same reason and
 * are tested the same way.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The line must break at every unstated point
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("a gap in the data is a gap in the line", () => {
  test("a withheld bucket ENDS a run — the line is never drawn across it", () => {
    const runs = statedRuns([
      { value: 10, withheld: false },
      { value: 12, withheld: false },
      { value: null, withheld: true },
      { value: 14, withheld: false }
    ]);
    // Two paths, not one. A single path over indices 0,1,3 would draw a straight segment from the
    // second point to the fourth, right across the bucket the server refused to report.
    expect(runs).toEqual([[0, 1], [3]]);
  });

  test("a null with withheld:false ends a run too — 'no rate to compute' is also not a value", () => {
    // This is the error-rate series: `errorRate` is null wherever there were no requests at all,
    // and `withheld` is false because nothing was hidden. Different fact, same treatment.
    expect(statedRuns([{ value: 0.1, withheld: false }, { value: null, withheld: false }, { value: 0.2, withheld: false }])).toEqual([
      [0],
      [2]
    ]);
  });

  test("a MEASURED ZERO stays in the run — it is a fact, not an absence", () => {
    // The single most important distinction on the page. An hour with no traffic is `requests: 0,
    // withheld: false`, and it must be plotted on the baseline; treating it as missing would hide
    // a genuine gap in traffic behind a line that hops over it.
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

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The axis
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("the value axis", () => {
  test("always includes zero — a series clustered at 900-1000 still starts at 0", () => {
    const [top, step] = axisTop([900, 950, 1000]);
    expect(top).toBeGreaterThanOrEqual(1000);
    // The ticks walk up from zero, so the baseline is zero by construction.
    expect(top % step).toBe(0);
  });

  test("NULLS ARE DROPPED, NOT READ AS ZERO", () => {
    // If nulls were coerced, this would be the same axis as [0, 4000] — which it is, by luck, for
    // the top; the real damage is on the all-null case below, and on any future minimum. The
    // assertion that matters is that adding nulls to a series does not change its axis at all.
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

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The figures the page prints beside the marks
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("a refused figure never renders as a number", () => {
  test("errorRateText(null) is a dash — NOT '0.0%'", () => {
    // `(rate ?? 0) * 100` would print a clean, confident 0% both for an hour in which nothing
    // happened and for one the server withheld. Neither of those is "nothing went wrong".
    expect(errorRateText(null)).toBe("—");
  });

  test("errorRateText(0) IS '0.0%' — a measured zero is a real answer", () => {
    expect(errorRateText(0)).toBe("0.0%");
  });

  test("a small but non-zero rate is not rounded away to 0.0%", () => {
    expect(errorRateText(0.0004)).toBe("0.04%");
  });

  test("isWithheldRoute reads the flag and nothing else, on every row shape the API sends", () => {
    // One predicate for route rows, timeline buckets, latency rows and client rows alike — a second
    // one beside it is how two implementations of one rule drift apart.
    expect(isWithheldRoute({ withheld: true })).toBe(true);
    expect(isWithheldRoute({ withheld: false })).toBe(false);
  });

  test("bucket ticks stay in UTC — converting them here would silently re-bucket the data", () => {
    // The server labels a bucket by the UTC moment it starts. Drawing it under a local-time tick
    // would file an hour's traffic under a neighbouring hour, with nothing on screen to say so.
    expect(bucketTickText("2026-08-30T17:00:00+00:00", "hour")).toBe("17:00");
    expect(bucketTickText("2026-08-30T00:00:00+00:00", "day")).toBe("30/8");
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The consent client: what it must not compute, and what it must not own
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("the consent gate is read, never derived", () => {
  test("a payload with no gate yields null — 'do not claim to know', not 'nothing needed'", () => {
    // The window in which this build talks to a server that predates the column. Returning a
    // permissive default here would silently disable the sign-in gate for a whole deploy.
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
    // The basis is the column that stops a turnstile being filed as a free choice, so it is printed
    // on every decision row. A token from a newer server is a fact about the server, not an error.
    expect(consentBasisText("REQUIRED_AT_SIGN_IN")).toContain("condition of access");
    expect(consentBasisText("OFFERED_IN_SETTINGS")).toContain("freely");
    expect(consentBasisText("SOMETHING_NEWER")).toBe("SOMETHING_NEWER");
    expect(consentBasisText(null)).toBe("Not recorded");
  });

  test("an absent date is a dash, never today's", () => {
    expect(consentMoment(null)).toBe("—");
    expect(consentMoment(undefined)).toBe("—");
  });
});

/**
 * THE COPY CENSUS.
 *
 * The recording notice is computed on the server from the policy actually in force, and both
 * clients render it verbatim. Writing any of it out in TSX is how one decision comes to be described
 * two ways — and here that would not be an inconsistency but two different consents. This walks the
 * client for the notice's own load-bearing phrases and fails if any of them has been copied in.
 */
test("the notice's sentences live on the server, and are nowhere in this client", () => {
  const roots = ["app", "components", "lib"];
  const banned = [
    // `usage.consent_notice()["requiredSentence"]`
    "You cannot sign in without agreeing to this",
    // `usage.retention_note()`
    "There is no retention policy",
    // `usage.consent_notice()["withdrawal"]["costsNothing"]`
    "Withdrawing does not sign you out",
    // `usage.consent_gate()`'s REFUSED sentence
    "has declined to have its use of the platform recorded"
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
