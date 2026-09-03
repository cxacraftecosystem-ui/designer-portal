import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { analysePayload, type MarketFindingsPayload, type MarketRow } from "@/lib/marketAnalysis";

/**
 * `lib/marketAnalysis.ts` PROVED EQUAL TO `backend/app/services/market_analysis.py`, case for case.
 *
 * ── WHY THIS SPEC IS THE WHOLE VALUE OF THE PORT ──────────────────────────────────────────────
 * The panel this feeds tells a designer that the price band they declared for a saree covers six of
 * eight observations, that a competitor sits in the 80th percentile, and that a SWOT point has no
 * evidence behind it anywhere in the interviews — and the report prints those same sentences into a
 * document submitted to a Development Commissioner's office. A port that is merely ROUGHLY the same
 * arithmetic is worse than no port: it would contradict the handset and the server about the same
 * workshop, and the designer would have no way to tell which of the three was lying.
 *
 * `market-analysis-sum-unit.spec.ts` is the sibling of this file and does NOT overlap it: that one
 * exercises `pySum` on its own, against numbers read off CPython, and is where the compensated-sum
 * argument is written out. This one runs the whole module against the shared table.
 *
 * ── THE EXPECTATIONS ARE NOT WRITTEN HERE ─────────────────────────────────────────────────────
 * `android/app/src/test/resources/dw-analysis-cases.json` and `dw-analysis-expected.json` are the
 * SHARED case table and the goldens REGENERATED FROM THE BACKEND MODULES THEMSELVES — the same two
 * files `DwAnalysisParityTest.kt` reads, and the same two `cost-integrity-port-unit.spec.ts` reads
 * for its half. So this spec compares the browser against CPython's own output rather than against
 * a fixture somebody wrote from what this file already did, which would prove only that it still
 * does it. The case table's own note says why the odd spellings in it must not be tidied: "a
 * hexadecimal scrap, Odia digits, a signed zero, a no-break space — each is a place the two
 * languages part company."
 *
 * The comparison is on the WHOLE payload — every band verdict and its message, every distribution's
 * seven rounded figures, the caution list and its order, the competitor percentiles, the SWOT
 * support sets, the cluster members. Nothing is spot-checked, so a divergence cannot hide in a key
 * this spec forgot to name.
 *
 * ── WHAT `analysePayload` IS, AND WHY IT IS THE THING COMPARED ────────────────────────────────
 * `analysePayload` is `marketFindingsPayload(analyse(input))` — the module's own two halves, in the
 * order the app calls them. `analyse` produces `Map`s and unrounded floats; the payload function is
 * what sorts the category and group keys, rounds to the endpoint's places and drops the internals.
 * The goldens are the ENDPOINT's shape (`GET /design-workshops/{id}/market-analysis`), so comparing
 * anything earlier would leave the rounding and the key ordering — two of the five places the
 * languages part company — outside the proof.
 *
 * ── THE PLACES THE TWO LANGUAGES ACTUALLY PART COMPANY, all reachable from here ───────────────
 * Named because each is a real bug someone will otherwise reintroduce while "simplifying":
 *
 *  1. **`sum()` is compensated; `+=` is not.** Since CPython 3.12 the builtin is Neumaier's
 *     algorithm, and `describe()`'s mean goes through it — `m29-mean-compensated-sum` is the case
 *     that fails the moment the port spells it `reduce((a, b) => a + b)`.
 *  2. **Rounding ties.** Python rounds half to EVEN; `toFixed` rounds half away from zero.
 *     `m25-half-even-median-and-mean` lands a median and a mean exactly on a tie.
 *  3. **Non-finite spellings.** Python prints `inf`, `-inf`, `nan`; JavaScript prints `Infinity`.
 *     `m11-non-finite-strings` feeds those words in as stored values.
 *  4. **Reading a stored MONEY value.** A fixed-2 string with grouping commas, Odia digits, a
 *     no-break space, a hexadecimal scrap — `m09` and `m28` cover the shared `asNumber`.
 *  5. **Key ordering, and it is Python's `str` ordering, not the browser's.** `byCategory` and
 *     `groupCounts` are dicts the server serialises in sorted order, and `localeCompare` is not
 *     that sort. `m22-swot-astral-name-tiebreak` and `m23-clusters-mixed-case` are where the two
 *     collations disagree; `comparePyStrings` is what the port compares with.
 *
 * ── ON A FAILURE, READ THE PATH AND NOT THE PAYLOAD ───────────────────────────────────────────
 * A `toEqual` over a payload this size prints two large objects and leaves a reader to find the one
 * key that moved. So the difference is located first and reported as a JSON path — `bands[0].
 * message`, `byCategory.SAREE.p75` — with the case name in the same sentence. The `toEqual` is
 * still made afterwards, unchanged and over the whole object, because it is the assertion that
 * cannot be weakened by a bug in the walker above it.
 */

/**
 * The Android test resources, which are the canonical home of the shared table.
 *
 * READ ACROSS THE REPOSITORY RATHER THAN COPIED, deliberately. A copy under `frontend/e2e/fixtures`
 * would be a second table to regenerate, and the day somebody regenerated one and not the other the
 * two clients would be proved equal to two different servers.
 */
const RESOURCES = join(__dirname, "..", "..", "android", "app", "src", "test", "resources");

type MarketCase = {
  name: string;
  comment?: string;
  responses: MarketRow[];
  competitors: MarketRow[];
  bands: MarketRow[];
  swot: MarketRow[];
};

const cases: MarketCase[] = JSON.parse(readFileSync(join(RESOURCES, "dw-analysis-cases.json"), "utf8")).market;
const expected: Record<string, MarketFindingsPayload> = JSON.parse(
  readFileSync(join(RESOURCES, "dw-analysis-expected.json"), "utf8")
);

/**
 * How a leaf is printed in a failure line.
 *
 * `-0` IS SPELLED OUT, and that is the whole reason this is not `JSON.stringify`. `JSON.stringify(-0)`
 * is `"0"`, so a signed zero against an unsigned one would report "expected 0, got 0" — a difference
 * that is real (both `Object.is` and `toEqual` refuse it) described as no difference at all, which is
 * the most expensive kind of failure message there is. `m10-negatives-and-signed-zero` is the case.
 *
 * EVERY NON-ASCII CODE POINT IS ESCAPED, for the same reason one rung down. This table is built out
 * of characters chosen to be invisible — a next-line U+0085, a narrow no-break space, Odia digits —
 * so the first run of this spec reported `"Kalpana" ≠ "Kalpana"`, two strings that differ by two
 * characters no terminal will draw. A reader who cannot see the difference cannot diagnose it, and
 * the diagnosis is the entire value of a parity failure.
 */
function show(value: unknown): string {
  if (typeof value === "number" && Object.is(value, -0)) return "-0";
  if (value === undefined) return "undefined";
  // Per CODE UNIT, deliberately — no `u` flag. An astral name (`m22-swot-astral-name-tiebreak` has
  // one) then prints as its two `\uXXXX` halves, which is the notation a reader can paste back,
  // rather than as a single escape built from the high surrogate alone.
  return JSON.stringify(value).replace(
    /[^\x20-\x7e]/g,
    (character) => `\\u${character.charCodeAt(0).toString(16).toUpperCase().padStart(4, "0")}`
  );
}

/**
 * Every place two payloads disagree, as JSON paths.
 *
 * `Object.is` AT THE LEAVES, not `===`, for the two values `===` gets wrong and this table contains:
 * a signed zero (see {@link show}) and `NaN`, which `m11-non-finite-strings` can produce. It is also
 * exactly what `toEqual` does at a leaf — jasmine's `eq` opens with `Object.is` — so this walker and
 * the assertion below cannot disagree about what "equal" means.
 *
 * Key sets are compared as SETS rather than in order: JSON object order is not part of the contract
 * here, `marketFindingsPayload` builds `byCategory` and `groupCounts` in sorted order and the ORDER
 * of an array is compared positionally below, which is where order does matter.
 */
function differences(actual: unknown, golden: unknown, path = "$"): string[] {
  if (Array.isArray(golden) || Array.isArray(actual)) {
    if (!Array.isArray(actual) || !Array.isArray(golden)) return [`${path}: ${show(actual)} ≠ ${show(golden)}`];
    if (actual.length !== golden.length) {
      return [`${path}: ${actual.length} entries, expected ${golden.length}`];
    }
    return golden.flatMap((entry, index) => differences(actual[index], entry, `${path}[${index}]`));
  }
  const bothObjects =
    typeof golden === "object" && golden !== null && typeof actual === "object" && actual !== null;
  if (bothObjects) {
    const left = actual as Record<string, unknown>;
    const right = golden as Record<string, unknown>;
    const keys = [...new Set([...Object.keys(left), ...Object.keys(right)])].sort();
    return keys.flatMap((key) => differences(left[key], right[key], `${path}.${key}`));
  }
  if (typeof golden === "object" || typeof actual === "object") {
    // One side is `null` and the other is a real object — `competitorPrices` and every entry of
    // `byCategory` are nullable, so this is a shape difference worth naming rather than walking.
    if (!Object.is(actual, golden)) return [`${path}: ${show(actual)} ≠ ${show(golden)}`];
    return [];
  }
  return Object.is(actual, golden) ? [] : [`${path}: ${show(actual)} ≠ ${show(golden)}`];
}

/**
 * ── THE TWO CASES THE WEB PORT ONCE FAILED, AND WHY THE SET OUTLIVES THEM ─────────────────────
 *
 * The first run of this spec, on 2026-09-03, found `m09-comma-grouped-and-unicode-digits` and
 * `m28-python-whitespace-forms` red. Both were the browser's fault and not the table's: the goldens
 * are regenerated from `backend/app/services/market_analysis.py`, so a divergence is a defect in
 * this port and never a fixture to be matched. Both were closed the same day, so the set below is
 * empty — kept rather than deleted, because the next divergence needs somewhere to be named and the
 * argument for how one is retired belongs with it.
 *
 * ── 1. `m09-comma-grouped-and-unicode-digits` — UNICODE DECIMAL DIGITS ────────────────────────
 *
 * Python's `float()` accepts any character in Unicode category Nd, so `float("୧୨୩")` is 123.0 and so
 * is `float("١٢٣")`. `asNumber` gated on an ASCII-only `PY_FLOAT` and then handed the text to
 * `Number()`, which answers NaN for both. Measured on this case: the browser read 8 of the 10 price
 * observations the server reads, and the sample it then described had a median of ₹670 against the
 * server's ₹545 and a 25th percentile of ₹338.75 against ₹123. Those are the figures stage 9 prints
 * and the report carries, so it was the panel and the .docx disagreeing about one workshop. Closed
 * by spelling `PY_FLOAT` with `\p{Nd}` and folding what it matches to ASCII before `Number()` sees
 * it — the grammar and the fold are one fix in two halves, and either alone still reads NaN.
 *
 * ── 2. `m28-python-whitespace-forms` — U+0085, WHICH `String.prototype.trim()` DOES NOT STRIP ──
 *
 * `str.strip()` follows `str.isspace()`, which is true for the next-line U+0085. ECMAScript's
 * WhiteSpace set is `Zs` plus a handful of named characters and does NOT include U+0085 (a Cc
 * control), so `trim()` correctly stripped this case's U+202F and U+2007 padding and walked straight
 * past its U+0085 padding. Every consequence in the case followed from that one line: a price padded
 * with it was unreadable and its observation dropped (3 respondent prices became 2); a competitor
 * padded the same way disappeared entirely, taking the whole `SAREE` category distribution with it;
 * a padded band bound read as absent, so a SOUND-able band was reported `NO_EVIDENCE` with the wrong
 * sentence under it; a respondent name kept its invisible padding into `supportedBy`; and an
 * `evidence` field holding nothing but padding was "non-empty" here and empty there, which flipped
 * `hasOwnEvidence`. The case table's own comment names this trap in as many words — it was written
 * for the Kotlin port and caught this one too. Closed by `pyStrip`, which replaced every `trim()`
 * that stood in for a Python `.strip()`.
 *
 * THE CHARACTERS ARE NAMED HERE AND NOT TYPED, as they now are in `lib/marketAnalysis.ts` too. This
 * comment used to quote the padded values literally, and a U+0085 inside a comment draws as nothing
 * at all: the sentence read as though a bare "500.00" were unreadable, which is nonsense, and no
 * reviewer could see why. `android/app/src/test/resources/dw-analysis-cases.json` is where they are
 * spelled out as escapes, beside the table's own note on why they must not be tidied away.
 *
 * SO THE NEXT ONE GOES IN THE SET, `test.fail()` AND NEVER `test.skip()`. A skipped case proves
 * nothing and stays silent forever; an expected-to-fail case RUNS the comparison every time and
 * turns the suite RED the moment somebody fixes the port and leaves the annotation behind. That is
 * exactly how these two left: the fix and the name, in one commit.
 */
const KNOWN_PORT_DIVERGENCES = new Set<string>();

test("the shared case table is present and is the one the handset reads", () => {
  // A spec that silently ran zero cases would be a green tick over an unproved port. The count is
  // asserted rather than the mere presence of the file, so a table trimmed by half is a failure.
  expect(cases.length).toBeGreaterThanOrEqual(29);
  for (const one of cases) expect(expected[`market/${one.name}`], `no golden for ${one.name}`).toBeTruthy();
});

test("the difference walker is discriminating, or every case below passes for the wrong reason", () => {
  /*
    THE WALKER IS TEST CODE THAT DECIDES WHETHER A TEST FAILS, so it gets its own case. A `differences`
    that returned `[]` for everything would turn all 29 assertions below into a pair where only the
    second one can fail — and the second one prints two large objects, which is the reporting this
    file exists to improve on.
  */
  expect(differences({ a: 1 }, { a: 1 })).toEqual([]);
  expect(differences({ a: 1 }, { a: 2 })).toEqual(["$.a: 1 ≠ 2"]);
  // The signed zero, both ways round, and named in the message rather than flattened to "0 ≠ 0".
  expect(differences({ a: -0 }, { a: 0 })).toEqual(["$.a: -0 ≠ 0"]);
  expect(differences({ a: NaN }, { a: NaN })).toEqual([]);
  // A nullable section against a real one, and a nested path.
  expect(differences({ p: null }, { p: { count: 1 } })).toEqual(["$.p: null ≠ {\"count\":1}"]);
  expect(differences({ b: [{ m: "x" }] }, { b: [{ m: "y" }] })).toEqual(['$.b[0].m: "x" ≠ "y"']);
  // A key present on one side only, which is how a payload that silently dropped a section reads.
  expect(differences({}, { cautions: [] })).toEqual(["$.cautions: undefined ≠ []"]);
  expect(differences({ b: [] }, { b: [1] })).toEqual(["$.b: 0 entries, expected 1"]);
});

test("every known divergence names a case that is actually in the table", () => {
  // Or the set above quietly stops excusing anything and this file goes back to claiming a clean
  // port. A renamed case must re-fail here rather than pass by disappearing.
  for (const name of KNOWN_PORT_DIVERGENCES) {
    expect(cases.some((one) => one.name === name), `${name} is no longer a case in the table`).toBe(true);
  }
});

for (const one of cases) {
  test(`market/${one.name} matches the backend byte for byte`, () => {
    // See KNOWN_PORT_DIVERGENCES. Expected-to-fail, so the comparison still RUNS and this run goes
    // red the day the port is corrected and the entry is left behind.
    if (KNOWN_PORT_DIVERGENCES.has(one.name)) test.fail();
    const actual = analysePayload({
      responses: one.responses,
      competitors: one.competitors,
      bands: one.bands,
      swot: one.swot
    });
    const golden = expected[`market/${one.name}`];
    /*
      THE PATHS FIRST, so a failure names the key that moved rather than printing the whole payload.
      The comment on `one.comment` is the case table's own note about what the case is FOR, and it is
      surfaced here because a reader looking at "bands[0].verdict" needs to know the case was built to
      sit exactly on the quantile floor.
    */
    expect(
      differences(actual, golden),
      `market/${one.name}${one.comment ? ` — ${one.comment}` : ""}`
    ).toEqual([]);
    /*
      And then the whole object, COMPARED DIRECTLY, not through `JSON.parse(JSON.stringify(actual))`.
      That round trip looks like a harmless normalisation and it destroys the one value this
      comparison most needs to keep: `JSON.stringify(-0)` is `"0"`. Python's `round(-0.0, 2)` is
      `-0.0` and a golden carrying it arrives as a real negative zero because it is PARSED rather than
      stringified. Passing that comparison by flattening the sign would be the spec agreeing with a
      bug rather than catching it — the same rule, for the same reason, as the cost port's spec.
    */
    expect(actual).toEqual(golden);
  });
}
