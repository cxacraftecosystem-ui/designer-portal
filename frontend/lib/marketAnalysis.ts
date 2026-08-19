/**
 * Stage 9's computed findings, in the browser — the port of `backend/app/services/market_analysis.py`.
 *
 * WHY A PORT AND NOT A FETCH. The Python module is pure on purpose: no database, no network, no
 * model, just rows in and findings out. That is what lets the same analysis run on the server, on
 * the handset and here, which is the only way a designer standing in the village where the survey
 * was taken gets the same findings as one at a desk. `GET /design-workshops/{id}/market-analysis`
 * exists for a report render and for a device that has never synced stage 8 — it is the fallback,
 * not the source. Everything below runs off the rows this browser already holds in its draft.
 *
 * IT IS A PORT, NOT A SECOND OPINION. When this file and `market_analysis.py` disagree, the Python
 * is right and this is broken. Three things make disagreement easy and are therefore spelled out
 * rather than delegated to a library:
 *
 *  - **The quantile method.** Linear interpolation on (n-1) positions, written out by hand, because
 *    three ports have to agree to the rupee and none of them may depend on a numerics library.
 *  - **The tokeniser.** `[\p{L}\p{M}]+` — and the `\p{M}` is load-bearing. Without it the virama in
 *    ରଙ୍ଗ and the matra in ଦାମ are word boundaries, every Odia word shatters into fragments the
 *    length floor then discards, and "unsupported by the survey" becomes the automatic verdict for
 *    exactly the fieldwork this application exists to collect. JavaScript's `\w` has the same defect
 *    as Python's; the property escapes are the fix in both.
 *  - **Rounding and money formatting.** Python's `round()` and `format(x, ',.0f')` round half to
 *    EVEN; JavaScript's `toFixed` and `Intl.NumberFormat` round half away from zero. They differ on
 *    every exact half — a median of ₹624.50 prints ₹624 on the server and ₹625 here — so
 *    {@link pyRound} implements Python's rule and every figure goes through it.
 *
 * IT REFUSES TO CONCLUDE FROM TOO LITTLE, identically. The sample floors and the UNVERIFIABLE /
 * NO_EVIDENCE verdicts are not defensive clutter to be simplified away: an analysis that produces a
 * confident verdict from four price expectations is worse than none, because the confidence is what
 * gets carried into a document a ministry reads.
 *
 * IT NEVER WRITES TO STAGE 9. Nothing here returns anything a form should be seeded from. The
 * designer's declared bands, SWOT and demand level stay exactly as typed; this produces findings
 * BESIDE them.
 */

/**
 * Below this many observations, quantiles are not reported at all. Five is the smallest sample for
 * which a quartile describes a spread rather than an accident of two numbers.
 */
export const MIN_SAMPLE_FOR_QUANTILES = 5;

/**
 * Below this many observations, a declared band gets no verdict. Deliberately higher than the
 * quantile floor: showing a spread from six numbers is honest, telling a designer their band is
 * WRONG from six numbers is not.
 */
export const MIN_SAMPLE_FOR_VERDICT = 8;

/**
 * A band is called NARROW when it covers less of the observed market than this. Not a statistical
 * threshold and not presented as one — it is the point at which "your band misses most of the
 * people you asked" becomes worth interrupting a designer to say.
 */
export const NARROW_COVERAGE = 0.55;

/**
 * Words too common to carry evidence. Kept small and English-only ON PURPOSE: the survey text is
 * frequently Odia, Hindi or transliterated, and a large English stop list would strip the rare words
 * that make a match meaningful in exactly the responses that matter most.
 *
 * Only words of three letters or more survive {@link MIN_TOKEN} anyway, so the one- and two-letter
 * articles are absent from this list rather than missing from it.
 */
const STOPWORDS = new Set([
  "and", "are", "but", "for", "from", "has", "have", "its", "not", "our", "that", "the",
  "their", "there", "they", "this", "was", "were", "will", "with", "you", "more", "most",
  "some", "such", "than", "then", "these", "those", "can", "could", "would", "should",
  "may", "might", "also", "very", "much", "many"
]);

/**
 * The shortest run of letters kept as a content word. Two-letter fragments are noise in every script
 * the survey is written in, and the stop list already removes the English ones that matter.
 */
const MIN_TOKEN = 3;

/**
 * How many content words a SWOT point and a survey response must share before the response is
 * offered as support. Two, because one shared word is a coincidence at this vocabulary size and
 * three misses a short point ("price too high") against a short response.
 */
const SUPPORT_OVERLAP = 2;

/**
 * A token is a maximal run of characters whose Unicode general category is L (letter) or M
 * (combining mark). See the file header for why the marks cannot be dropped — this single character
 * class is the difference between an Odia survey being analysed and being reported as empty.
 */
const WORD_RUN = /[\p{L}\p{M}]+/gu;

/**
 * What Python's `float()` accepts, once whitespace and grouping commas are gone.
 *
 * `Number()` is NOT that grammar: it reads `"0x1A"` as 26 and `""` as 0, both of which Python
 * refuses, so a hexadecimal-looking scrap in a price column would become a price here and nothing
 * there. Underscores are allowed between digits because Python allows them. `inf`/`nan` are
 * deliberately absent — they parse in Python and are then rejected as non-finite, so refusing them
 * one step earlier is the same answer.
 */
const PY_FLOAT = /^[+-]?(?:\d+(?:_\d+)*(?:\.(?:\d+(?:_\d+)*)?)?|\.\d+(?:_\d+)*)(?:[eE][+-]?\d+(?:_\d+)*)?$/;

/* ────────────────────────────────────────────────────────────────────────────
 * Python-compatible primitives
 *
 * Every helper in this block exists because the obvious JavaScript answer is subtly not Python's.
 * They are not general utilities and belong to nothing else.
 * ──────────────────────────────────────────────────────────────────────────── */

/** One row of a stage entity, exactly as it is stored. `DwRow` and a decoded API row both fit. */
export type MarketRow = Record<string, unknown>;

/** Whether Python would read this value as falsy — which is what `value or ""` turns into `""`. */
function pythonFalsy(value: unknown): boolean {
  if (value === null || value === undefined || value === false || value === 0 || value === "") return true;
  if (Array.isArray(value)) return value.length === 0;
  // NaN is deliberately NOT falsy: it is truthy in Python, so `str(nan or "")` is `"nan"`, and
  // JavaScript's `!NaN` would have produced `""` and a different token set.
  if (typeof value === "object") return Object.keys(value as object).length === 0;
  return false;
}

/**
 * Python's `str(value)`, as far as the tokeniser can tell the difference.
 *
 * Only letter runs survive tokenising, so an exact `repr` is not needed — what is needed is the same
 * letters in the same order. `JSON.stringify` gives that for arrays and objects (`{"a":"blue"}` and
 * `{'a': 'blue'}` tokenise identically), and the three scalars whose Python spelling differs are
 * written out.
 */
function pyStr(value: unknown): string {
  if (typeof value === "string") return value;
  if (value === null || value === undefined) return "None";
  if (value === true) return "True";
  if (value === false) return "False";
  if (typeof value === "number") return String(value);
  try {
    return JSON.stringify(value) ?? "";
  } catch {
    return "";
  }
}

/** Python's `str(value or "")`. */
function pyText(value: unknown): string {
  return pythonFalsy(value) ? "" : pyStr(value);
}

/**
 * Python's string ordering, which is by CODE POINT.
 *
 * JavaScript's `<` compares UTF-16 code units, so an astral character sorts before U+E000 there and
 * after it in Python. Survey tags and respondent names are the things being sorted and a stable,
 * identical order across the three clients is the only reason any of this is sorted at all.
 */
function comparePyStrings(a: string, b: string): number {
  const left = [...a];
  const right = [...b];
  const shared = Math.min(left.length, right.length);
  for (let i = 0; i < shared; i += 1) {
    const x = left[i].codePointAt(0) ?? 0;
    const y = right[i].codePointAt(0) ?? 0;
    if (x !== y) return x < y ? -1 : 1;
  }
  return left.length === right.length ? 0 : left.length < right.length ? -1 : 1;
}

/** Add one to a string of decimal digits, growing it when it carries past the front. */
function addOne(digits: string): string {
  const out = digits.split("");
  let index = out.length - 1;
  while (index >= 0) {
    if (out[index] === "9") {
      out[index] = "0";
      index -= 1;
      continue;
    }
    out[index] = String(Number(out[index]) + 1);
    return out.join("");
  }
  return `1${out.join("")}`;
}

/**
 * Python's `sum()` over floats, which since CPython 3.12 is COMPENSATED and not a running total.
 *
 * A `for` loop and `reduce((a, b) => a + b)` are both naive accumulators. CPython 3.12 gave `sum()`
 * the improved Kahan-Babuška algorithm by Neumaier, and `backend/Dockerfile` pins
 * `PYTHON_VERSION=3.12`, so the compensated answer is the one that actually ships and the obvious
 * JavaScript is the one that is wrong. The two agree until a sample mixes magnitudes far enough
 * apart that the small values fall off the bottom of the accumulator as they are added; if the
 * extremes then cancel, the server still has the small ones and a running total has already lost
 * them. Sorted prices of `[-1e308, 400, 600, 1e308]` give the server a mean of 250 and a running
 * total a mean of 0 — the same stage 9 figure, in two copies of one report.
 *
 * THE COMPENSATION IS FOLDED BACK ONLY WHILE THE RUNNING TOTAL IS FINITE. The compensation term for
 * a step that overflows is itself infinite, so adding it back unconditionally turns a real overflow
 * into a NaN and prints "₹nan" against the server's "₹inf".
 */
export function pySum(values: Iterable<number>): number {
  let total = 0;
  let compensation = 0;
  for (const value of values) {
    const stepped = total + value;
    // The larger magnitude keeps its bits; the smaller is what the step lost, and that is exactly
    // what the compensation has to carry.
    compensation +=
      Math.abs(total) >= Math.abs(value) ? total - stepped + value : value - stepped + total;
    total = stepped;
  }
  return Number.isFinite(total) ? total + compensation : total;
}

/**
 * Python's `round(value, digits)` — correct rounding of the EXACT binary value, ties to even.
 *
 * THIS IS NOT `toFixed`. `(0.125).toFixed(2)` is `"0.13"` because the specification says to pick the
 * larger candidate on a tie; `round(0.125, 2)` is `0.12` because Python rounds to the even digit.
 * Every figure in the payload goes through `round()` on the server, so a coverage of 1/32 or a mean
 * of ₹0.125 would differ in the last place between the two clients — and the whole claim this module
 * makes is that they do not.
 *
 * `toFixed(digits + 25)` is the exact decimal expansion as far as it can matter: a genuine tie means
 * the expansion terminates at `digits + 1`, so anything beyond is zeros, and a non-tie is decided
 * long before twenty-five further digits.
 */
export function pyRound(value: number, digits: number): number {
  if (!Number.isFinite(value)) return value;
  /*
    TWO EXITS BEFORE THE DECIMAL EXPANSION, EACH FOR A VALUE THIS FUNCTION USED TO GET WRONG.

    ABOVE 2^53 EVERY DOUBLE IS AN INTEGER, so rounding one to any number of decimal places is the
    identity — which is exactly what `round(1e308, 2)` answers in Python. The expansion below could
    not reach that answer: `toFixed` is specified to fall back to `ToString(x)` at 1e21 and above, so
    `(1e308).toFixed(27)` is the string "1e+308", the digit-walking then builds "1e+308.00", and
    `Number` of that is NaN. It reached a real caution — a cost line entered as `1e308` printed
    "₹nan" where the server prints "₹inf" — and it is pinned by `k28-orphan-total-overflows`.

    NEGATIVE ZERO IS A DISTINCT VALUE AND PYTHON KEEPS IT: `round(-0.0, 2)` is `-0.0`, and the JSON
    a client compares against carries `-0`. `value < 0` is false for `-0`, so the sign was dropped
    and a difference of exactly zero on a sheet whose declared figure is below its computed one came
    out as `0` where the server says `-0`. `money0` and `fixed0` beside this already made the same
    distinction; this did not. Pinned by `k26-live-workshop`.
  */
  if (Math.abs(value) >= Number.MAX_SAFE_INTEGER) return value;
  const negative = value < 0 || Object.is(value, -0);
  const text = Math.abs(value).toFixed(Math.min(100, digits + 25));
  const dot = text.indexOf(".");
  const whole = dot < 0 ? text : text.slice(0, dot);
  const fraction = dot < 0 ? "" : text.slice(dot + 1);
  const kept = fraction.slice(0, digits).padEnd(digits, "0");
  const rest = fraction.slice(digits);

  let carry = false;
  const lead = rest ? rest.charCodeAt(0) - 48 : 0;
  if (lead > 5) {
    carry = true;
  } else if (lead === 5) {
    if (/[1-9]/.test(rest.slice(1))) carry = true;
    else {
      const combined = whole + kept;
      carry = (combined.charCodeAt(combined.length - 1) - 48) % 2 === 1;
    }
  }

  let all = whole + kept;
  if (carry) all = addOne(all);
  const cut = all.length - digits;
  const magnitude = digits > 0 ? `${all.slice(0, cut) || "0"}.${all.slice(cut)}` : all;
  const result = Number(magnitude);
  return negative ? -result : result;
}

/** Python's `format(value, ".0f")` — half to even, no grouping. */
function fixed0(value: number): string {
  const negative = value < 0 || Object.is(value, -0);
  return `${negative ? "-" : ""}${pyRound(Math.abs(value), 0).toFixed(0)}`;
}

/**
 * Python's `format(value, ",.0f")` — half to even, with grouping commas.
 *
 * Not `Intl.NumberFormat`: its default rounding mode is `halfExpand`, and it is also locale-sensitive
 * where this must not be (an Indian locale groups as 1,00,000, and the server prints 100,000).
 */
function money0(value: number): string {
  const negative = value < 0 || Object.is(value, -0);
  const body = pyRound(Math.abs(value), 0).toFixed(0);
  return `${negative ? "-" : ""}${body.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}`;
}

/**
 * The rupee figures in the sentences above, for a screen that prints one BESIDE them.
 *
 * Exported so a summary card and the verdict under it cannot group differently: ₹1,00,000 in one and
 * ₹100,000 in the other read as two different numbers to anybody skimming, and the sentence is the
 * one that has to be matched because it is the server's.
 */
export function formatRupeeAmount(value: number): string {
  return money0(value);
}

/** Content words of `text`, lowercased. See {@link WORD_RUN} for the tokenising rule. */
function tokens(text: string): Set<string> {
  const out = new Set<string>();
  for (const match of (text || "").matchAll(WORD_RUN)) {
    const word = match[0].toLowerCase();
    // Counted in CODE POINTS, as Python counts them: `.length` would read an astral letter as two
    // and let a one-character word through the floor.
    if ([...word].length >= MIN_TOKEN && !STOPWORDS.has(word)) out.add(word);
  }
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reading the stored values
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `value` as a finite number, or null.
 *
 * MONEY is stored as a fixed-2 STRING and may carry grouping commas, so this is the one place that
 * knows how to read it. Non-finite is rejected rather than propagated: a NaN entering a quantile
 * silently poisons every figure downstream of it, and a report that prints "₹ NaN" has already been
 * submitted by the time anybody notices.
 */
export function asNumber(value: unknown): number | null {
  if (value === null || value === undefined || typeof value === "boolean") return null;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  // Python reads `str(value)`, and every other shape a stage field can hold — a tag list, a GEO
  // object, a rich-text document — stringifies to something `float()` refuses. Refusing them here is
  // the same answer reached sooner, and it closes the one place the two languages disagree:
  // `String([5])` is `"5"`, so a one-element list would have become a price here and nothing there.
  if (typeof value !== "string") return null;
  const text = value.trim().replace(/,/g, "");
  if (!text) return null;
  if (!PY_FLOAT.test(text)) return null;
  const number = Number(text.replace(/_/g, ""));
  return Number.isFinite(number) ? number : null;
}

/**
 * The `q`-quantile of an ALREADY SORTED list, by linear interpolation on (n-1) positions.
 *
 * Specified rather than delegated, because this and `market_analysis.quantile` must produce the same
 * rupee figure. The method is numpy's default:
 *
 *     position = q * (n - 1);  lower = floor(position);  upper = ceil(position)
 *     result   = v[lower] + (v[upper] - v[lower]) * (position - lower)
 *
 * The caller sorts. That is not laziness — every caller here computes several quantiles from one
 * sample, and sorting inside would turn one sort into five.
 */
export function quantile(sortedValues: number[], q: number): number {
  // Zero is a price. Returning it for "no data" would print ₹0 into a cost table.
  if (!sortedValues.length) throw new Error("quantile of an empty sample");
  if (sortedValues.length === 1) return sortedValues[0];
  const position = q * (sortedValues.length - 1);
  const lower = Math.floor(position);
  const upper = Math.ceil(position);
  if (lower === upper) return sortedValues[lower];
  return sortedValues[lower] + (sortedValues[upper] - sortedValues[lower]) * (position - lower);
}

/* ────────────────────────────────────────────────────────────────────────────
 * The findings
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One price somebody named, and who named it.
 *
 * `label` exists so a finding can be traced back to the row that produced it. An analysis a designer
 * cannot audit is one they have to take on faith, and the whole purpose of this module is to replace
 * faith with arithmetic.
 */
export type PriceObservation = {
  amount: number;
  category: string;
  /** RESPONDENT | COMPETITOR — what a buyer will pay and what the shelf charges are different facts. */
  source: "RESPONDENT" | "COMPETITOR";
  /** The RESPONDENT_GROUP token, for respondent observations. */
  group: string;
  label: string;
};

/** A sample's shape. `quantilesReported` is false when the sample was too small to describe. */
export type Distribution = {
  count: number;
  minimum: number;
  maximum: number;
  mean: number;
  p25: number;
  median: number;
  p75: number;
  quantilesReported: boolean;
};

/**
 * The distribution of `values`, or null for an empty sample.
 *
 * Below {@link MIN_SAMPLE_FOR_QUANTILES} the quartiles are filled with the median and
 * `quantilesReported` is false, so a caller that ignores the flag shows something defensible rather
 * than a quartile computed from two numbers.
 */
export function describe(values: number[]): Distribution | null {
  if (!values.length) return null;
  const ordered = [...values].sort((a, b) => a - b);
  const count = ordered.length;
  const median = quantile(ordered, 0.5);
  const enough = count >= MIN_SAMPLE_FOR_QUANTILES;
  // Summed in sorted order and through {@link pySum}, because the server's `sum(ordered)` differs
  // from the obvious loop in BOTH respects: order, because floating-point addition is not
  // associative, so a mean summed over the original order can differ from one summed over the
  // sorted array; and algorithm, because CPython 3.12's `sum()` is compensated.
  const total = pySum(ordered);
  return {
    count,
    minimum: ordered[0],
    maximum: ordered[count - 1],
    mean: total / count,
    p25: enough ? quantile(ordered, 0.25) : median,
    median,
    p75: enough ? quantile(ordered, 0.75) : median,
    quantilesReported: enough
  };
}

/**
 * What the survey says about one declared price band.
 *
 *   SOUND         the band covers most of the observed market
 *   NARROW        the band excludes most of it, without being wholly on one side
 *   LOW / HIGH    the band sits below / above where the evidence clusters
 *   UNVERIFIABLE  too few observations to say anything (NOT a criticism of the band)
 *   NO_EVIDENCE   no observations in this category at all
 */
export type BandVerdictKind = "SOUND" | "NARROW" | "LOW" | "HIGH" | "UNVERIFIABLE" | "NO_EVIDENCE";

export type BandVerdict = {
  category: string;
  declaredLow: number | null;
  declaredHigh: number | null;
  evidence: Distribution | null;
  inside: number;
  below: number;
  above: number;
  coverage: number;
  verdict: BandVerdictKind;
  message: string;
};

/** One sentence a designer can act on, naming the numbers rather than grading the designer. */
function bandMessage(
  verdict: BandVerdictKind,
  category: string,
  low: number,
  high: number,
  dist: Distribution,
  inside: number,
  below: number,
  above: number
): string {
  const label = category || "this category";
  const range = `₹${money0(low)}–${money0(high)}`;
  const median = `₹${money0(dist.median)}`;
  if (verdict === "SOUND") {
    return `The band ${range} covers ${inside} of ${dist.count} price observations for ${label}; ` +
      `the median observed is ${median}.`;
  }
  if (verdict === "LOW") {
    return `The band ${range} sits below the evidence for ${label}: ${above} of ${dist.count} ` +
      `observations are above it, and the median observed is ${median}. The prototypes may be ` +
      `priced under what buyers said.`;
  }
  if (verdict === "HIGH") {
    return `The band ${range} sits above the evidence for ${label}: ${below} of ${dist.count} ` +
      `observations are below it, and the median observed is ${median}.`;
  }
  return `The band ${range} covers only ${inside} of ${dist.count} observations for ${label} ` +
    `(${below} below, ${above} above; median ${median}). Widening it, or splitting the category, ` +
    `would match what was recorded.`;
}

/**
 * Compare one declared band against every price observed in its category.
 *
 * A reversed band (low > high) is read in the order the designer meant rather than refused: the two
 * boxes are adjacent on a phone and transposing them is a slip, not a claim, and answering a slip
 * with "no verdict" would hide the real finding underneath it.
 */
export function judgeBand(
  category: string,
  low: unknown,
  high: unknown,
  observations: PriceObservation[]
): BandVerdict {
  let declaredLow = asNumber(low);
  let declaredHigh = asNumber(high);
  if (declaredLow !== null && declaredHigh !== null && declaredLow > declaredHigh) {
    const swap = declaredLow;
    declaredLow = declaredHigh;
    declaredHigh = swap;
  }

  const values = observations.map((observation) => observation.amount);
  const dist = describe(values);
  const named = category || "this category";

  if (dist === null) {
    return {
      category, declaredLow, declaredHigh, evidence: null,
      inside: 0, below: 0, above: 0, coverage: 0, verdict: "NO_EVIDENCE",
      message: `No price was recorded in stage 8 for ${named}, so this band cannot be checked ` +
        `against the survey.`
    };
  }

  if (declaredLow === null || declaredHigh === null) {
    return {
      category, declaredLow, declaredHigh, evidence: dist,
      inside: 0, below: 0, above: 0, coverage: 0, verdict: "NO_EVIDENCE",
      message: `${dist.count} price observation(s) exist for ${named}, but the band itself is incomplete.`
    };
  }

  let inside = 0;
  let below = 0;
  let above = 0;
  for (const value of values) {
    if (value >= declaredLow && value <= declaredHigh) inside += 1;
    if (value < declaredLow) below += 1;
    if (value > declaredHigh) above += 1;
  }
  const coverage = inside / dist.count;

  if (dist.count < MIN_SAMPLE_FOR_VERDICT) {
    // NOT a criticism of the band. Seven observations cannot tell a designer their considered band
    // is wrong, and a designer told so by a machine counting seven numbers will — correctly — stop
    // believing the tool. The counts are still reported; they may look and decide for themselves.
    return {
      category, declaredLow, declaredHigh, evidence: dist, inside, below, above, coverage,
      verdict: "UNVERIFIABLE",
      message: `Only ${dist.count} price observation(s) were recorded for ${named} — too few to ` +
        `judge the band against. ${inside} of them fall inside it.`
    };
  }

  let verdict: BandVerdictKind;
  if (coverage >= NARROW_COVERAGE) verdict = "SOUND";
  else if (above > below * 2) verdict = "LOW";
  else if (below > above * 2) verdict = "HIGH";
  else verdict = "NARROW";

  return {
    category, declaredLow, declaredHigh, evidence: dist, inside, below, above, coverage, verdict,
    message: bandMessage(verdict, category, declaredLow, declaredHigh, dist, inside, below, above)
  };
}

/** Where one competitor product sits among the prices observed in its category. */
export type CompetitorPosition = {
  name: string;
  seller: string;
  category: string;
  price: number;
  percentile: number | null;
  versusMedian: number | null;
  note: string;
};

/**
 * Place each competitor product in its category's respondent price distribution.
 *
 * Positioned against what BUYERS said they would pay, not against the other competitors — the useful
 * question for a designer is "is this shelf price above or below what the people we asked are
 * willing to spend", and a competitor-only comparison cannot answer it.
 */
export function positionCompetitors(
  competitors: MarketRow[],
  observations: PriceObservation[]
): CompetitorPosition[] {
  const byCategory = new Map<string, number[]>();
  const pooled: number[] = [];
  for (const observation of observations) {
    if (observation.source !== "RESPONDENT") continue;
    pooled.push(observation.amount);
    if (observation.category) {
      const bucket = byCategory.get(observation.category);
      if (bucket) bucket.push(observation.amount);
      else byCategory.set(observation.category, [observation.amount]);
    }
  }
  for (const values of byCategory.values()) values.sort((a, b) => a - b);
  pooled.sort((a, b) => a - b);

  const out: CompetitorPosition[] = [];
  for (const row of competitors) {
    const price = asNumber(row.price);
    // Skipped rather than counted as zero: a competitor with no price recorded is a row somebody has
    // not finished, and ₹0 on a shelf is a claim about the market that nobody made.
    if (price === null) continue;
    const category = pyText(row.category);
    const name = pyText(row.name).trim() || "Unnamed product";
    const seller = pyText(row.seller).trim();

    /*
      CATEGORY-MATCHED IF POSSIBLE, POOLED IF NOT, AND THE MESSAGE SAYS WHICH.

      A respondent's price expectation carries NO category — stage 8 asks what they would pay, not
      what for — so a strict category lookup finds nothing for almost every competitor and the whole
      section reads "too few buyer price expectations" against a survey of forty people. That is not
      a cautious answer, it is a useless one, and it is the opposite of what `judgeBand` does above:
      that one already pools the uncategorised observations. The two must not disagree about the
      same sample.

      So the fallback is used and DECLARED. "Against all buyers asked" is a weaker statement than
      "against buyers asked about this category", and a designer has to be able to tell them apart to
      know how much weight to put on the number.
    */
    let sample = byCategory.get(category) ?? [];
    const matched = sample.length >= MIN_SAMPLE_FOR_QUANTILES;
    if (!matched) sample = pooled;
    if (sample.length < MIN_SAMPLE_FOR_QUANTILES) {
      out.push({
        name, seller, category, price, percentile: null, versusMedian: null,
        note: `₹${money0(price)}. Too few buyer price expectations were recorded in the survey to ` +
          `place this against them.`
      });
      continue;
    }

    let atOrBelow = 0;
    for (const value of sample) if (value <= price) atOrBelow += 1;
    const percentile = atOrBelow / sample.length;
    const median = quantile(sample, 0.5);
    const whose = matched
      ? `the ${sample.length} buyers asked about ${category}`
      : `all ${sample.length} buyers asked`;
    // "Above 20% of buyers" is a true sentence that reads as a boast. Say "below 80%" instead.
    const comparison = percentile >= 0.5 ? "above" : "below";
    const share = percentile >= 0.5 ? percentile : 1 - percentile;
    out.push({
      name, seller, category, price, percentile, versusMedian: price - median,
      note: `₹${money0(price)} is ${comparison} what ${fixed0(share * 100)}% of ${whose} said they ` +
        `would pay (their median: ₹${money0(median)}).`
    });
  }
  return out;
}

/** Whether one SWOT point is backed by something a respondent actually said. */
export type SwotSupport = {
  kind: string;
  point: string;
  supportedBy: string[];
  overlap: number;
  hasOwnEvidence: boolean;
  /** A point that cited its own evidence is supported by definition — the designer named a source. */
  supported: boolean;
};

/**
 * Match each SWOT point to the survey responses that share its vocabulary.
 *
 * THIS IS A RETRIEVAL AID, NOT A JUDGEMENT, and the distinction is the reason it can ship without a
 * model behind it. Shared words mean a response is worth reading next to the point, nothing more;
 * the designer decides whether it supports the claim. What it does reliably is the negative case — a
 * SWOT point sharing no vocabulary with any of forty responses and carrying no evidence of its own
 * is an assertion, and saying so is worth more than any positive match.
 */
export function linkSwotEvidence(swot: MarketRow[], responses: MarketRow[]): SwotSupport[] {
  const prepared: { name: string; tokens: Set<string> }[] = [];
  for (const row of responses) {
    let text = ["response", "productsDiscussed", "place"].map((key) => pyText(row[key])).join(" ");
    // A tag list contributes its members again as plain words. It costs nothing (the token set
    // already holds them) and it is what the server does, so the two cannot drift apart here.
    if (Array.isArray(row.productsDiscussed)) {
      text += ` ${row.productsDiscussed.map((tag) => pyStr(tag)).join(" ")}`;
    }
    prepared.push({ name: pyText(row.respondentName).trim() || "A respondent", tokens: tokens(text) });
  }

  const out: SwotSupport[] = [];
  for (const row of swot) {
    const point = pyText(row.point).trim();
    // A blank point is dropped rather than reported unsupported: an empty row in the table is a row
    // somebody has not typed yet, not an unevidenced claim.
    if (!point) continue;
    const kind = pyText(row.kind);
    const own = pyText(row.evidence).trim().length > 0;
    const wanted = tokens(point);
    const matches: { shared: number; name: string }[] = [];
    for (const candidate of prepared) {
      let shared = 0;
      for (const word of wanted) if (candidate.tokens.has(word)) shared += 1;
      if (shared >= SUPPORT_OVERLAP) matches.push({ shared, name: candidate.name });
    }
    matches.sort((a, b) => (b.shared - a.shared) || comparePyStrings(a.name, b.name));
    const supportedBy = matches.slice(0, 5).map((match) => match.name);
    out.push({
      kind,
      point,
      supportedBy,
      overlap: matches.length ? matches[0].shared : 0,
      hasOwnEvidence: own,
      supported: own || supportedBy.length > 0
    });
  }
  return out;
}

/** Products that respondents mentioned together, and how often. */
export type TrendCluster = {
  members: string[];
  mentions: number;
  coOccurrences: number;
};

/**
 * Group the products respondents discussed by how often they came up in the same conversation.
 *
 * Co-occurrence rather than a topic model, deliberately. It is explainable to the designer whose
 * data it is ("these three were mentioned together nine times"), it is stable — the same rows give
 * the same clusters every run, which a k-means seeded at random does not — and it runs in
 * milliseconds on a handset. A model here would buy nothing that a designer could check.
 *
 * Single-link agglomeration over pairs seen at least twice: two products join when they were
 * discussed together, and a chain of such pairs becomes one cluster.
 */
export function clusterProducts(
  responses: MarketRow[],
  { minimumMentions = 2, limit = 8 }: { minimumMentions?: number; limit?: number } = {}
): TrendCluster[] {
  const tagLists: string[][] = [];
  for (const row of responses) {
    const raw = row.productsDiscussed;
    // Android and the web both hand TAGS across as a list, but a hand-edited draft may not.
    const tags: unknown[] = Array.isArray(raw) ? raw : pyText(raw).split(",");
    const unique = new Set<string>();
    for (const tag of tags) {
      const text = pyStr(tag).trim();
      if (text) unique.add(text.toLowerCase());
    }
    const cleaned = [...unique].sort(comparePyStrings);
    if (cleaned.length) tagLists.push(cleaned);
  }

  const mentions = new Map<string, number>();
  const pairs = new Map<string, { a: string; b: string; seen: number }>();
  for (const tags of tagLists) {
    for (const tag of tags) mentions.set(tag, (mentions.get(tag) ?? 0) + 1);
    for (let i = 0; i < tags.length; i += 1) {
      for (let j = i + 1; j < tags.length; j += 1) {
        // Python keys this on a TUPLE, which cannot be ambiguous. A joined STRING key can be:
        // on a space, ["a b", "c"] and ["a", "b c"] produce the same key, and "floor mat" is a
        // real tag in this survey. NUL cannot occur in a tag, so it is the one safe separator.
        const key = `${tags[i]}\u0000${tags[j]}`;
        const existing = pairs.get(key);
        if (existing) existing.seen += 1;
        else pairs.set(key, { a: tags[i], b: tags[j], seen: 1 });
      }
    }
  }

  // Union-find over the pairs that recur. A pair seen once is two people who happened to say the
  // same two words; at two it is worth showing.
  const parent = new Map<string, string>();
  for (const tag of mentions.keys()) parent.set(tag, tag);
  const find = (start: string): string => {
    let node = start;
    while (parent.get(node) !== node) {
      const grandparent = parent.get(parent.get(node) as string) as string;
      parent.set(node, grandparent);
      node = grandparent;
    }
    return node;
  };

  const joined = new Map<string, number>();
  for (const pair of pairs.values()) {
    if (pair.seen < minimumMentions) continue;
    const rootA = find(pair.a);
    const rootB = find(pair.b);
    if (rootA !== rootB) parent.set(rootB, rootA);
    const root = find(pair.a);
    joined.set(root, (joined.get(root) ?? 0) + pair.seen);
  }

  const groups = new Map<string, string[]>();
  for (const tag of mentions.keys()) {
    const root = find(tag);
    const bucket = groups.get(root);
    if (bucket) bucket.push(tag);
    else groups.set(root, [tag]);
  }

  const clusters: TrendCluster[] = [];
  for (const [root, members] of groups) {
    // A single mention is not a trend and is not promoted to one.
    if (!(members.length > 1 || (mentions.get(members[0]) ?? 0) >= minimumMentions)) continue;
    let total = 0;
    for (const member of members) total += mentions.get(member) ?? 0;
    clusters.push({
      members: [...members].sort(comparePyStrings),
      mentions: total,
      coOccurrences: joined.get(root) ?? 0
    });
  }
  clusters.sort((a, b) =>
    (b.mentions - a.mentions) || (b.members.length - a.members.length) || compareMembers(a.members, b.members)
  );
  return clusters.slice(0, limit);
}

/** Python's tuple comparison over two member lists: elementwise, then by length. */
function compareMembers(a: string[], b: string[]): number {
  const shared = Math.min(a.length, b.length);
  for (let i = 0; i < shared; i += 1) {
    const order = comparePyStrings(a[i], b[i]);
    if (order !== 0) return order;
  }
  return a.length - b.length;
}

/** Everything this module concluded, plus what it could not conclude and why. */
export type MarketFindings = {
  observations: number;
  respondentPrices: Distribution | null;
  competitorPrices: Distribution | null;
  byCategory: Map<string, Distribution>;
  bands: BandVerdict[];
  competitors: CompetitorPosition[];
  swot: SwotSupport[];
  clusters: TrendCluster[];
  groupCounts: Map<string, number>;
  cautions: string[];
};

/**
 * Every price in stage 8, tagged with where it came from.
 *
 * A respondent's `priceExpectation` carries no category of its own — the survey form asks what they
 * would pay, not what for — so it inherits `defaultCategory` when one is known and is otherwise
 * pooled under "". Pretending to a category it does not have would put a buyer's answer about a
 * stole into the price band for floor coverings.
 */
export function collectObservations(
  responses: MarketRow[],
  competitors: MarketRow[],
  defaultCategory = ""
): PriceObservation[] {
  const out: PriceObservation[] = [];
  for (const row of responses) {
    const amount = asNumber(row.priceExpectation);
    if (amount === null) continue;
    out.push({
      amount,
      category: defaultCategory,
      source: "RESPONDENT",
      group: pyText(row.respondentGroup),
      label: pyText(row.respondentName).trim() || "A respondent"
    });
  }
  for (const row of competitors) {
    const amount = asNumber(row.price);
    if (amount === null) continue;
    out.push({
      amount,
      category: pyText(row.category),
      source: "COMPETITOR",
      group: "",
      label: pyText(row.name).trim() || "A competitor product"
    });
  }
  return out;
}

/**
 * The whole of stage 9's Advanced tier, from the rows of stages 8 and 9.
 *
 * Every argument is a list of stage entry `data` objects exactly as they are stored, so a caller has
 * nothing to reshape and the browser can pass what it already holds.
 */
export function analyse({
  responses,
  competitors,
  bands,
  swot
}: {
  responses: MarketRow[];
  competitors: MarketRow[];
  bands: MarketRow[];
  swot: MarketRow[];
}): MarketFindings {
  const observations = collectObservations(responses, competitors);
  const respondentValues: number[] = [];
  const competitorValues: number[] = [];
  for (const observation of observations) {
    if (observation.source === "RESPONDENT") respondentValues.push(observation.amount);
    else competitorValues.push(observation.amount);
  }

  const grouped = new Map<string, number[]>();
  for (const observation of observations) {
    const bucket = grouped.get(observation.category);
    if (bucket) bucket.push(observation.amount);
    else grouped.set(observation.category, [observation.amount]);
  }
  const byCategory = new Map<string, Distribution>();
  for (const [category, values] of grouped) {
    const described = describe(values);
    if (described) byCategory.set(category, described);
  }

  const bandVerdicts = bands.map((row) => {
    const category = pyText(row.category);
    return judgeBand(
      category,
      row.lowPrice,
      row.highPrice,
      // Uncategorised observations are pooled into every band, which is what makes a real survey
      // answerable at all: stage 8 records what a buyer would pay without asking what for.
      observations.filter((observation) => observation.category === category || !observation.category)
    );
  });

  const groupCounts = new Map<string, number>();
  for (const observation of observations) {
    if (observation.source !== "RESPONDENT" || !observation.group) continue;
    groupCounts.set(observation.group, (groupCounts.get(observation.group) ?? 0) + 1);
  }

  const cautions: string[] = [];
  if (respondentValues.length && respondentValues.length < MIN_SAMPLE_FOR_VERDICT) {
    cautions.push(
      `Only ${respondentValues.length} respondent price expectation(s) were recorded. The figures ` +
        `below describe what was collected; they are not a market estimate.`
    );
  }
  if (groupCounts.size) {
    // The first-inserted group wins a tie, which is what Python's `most_common` does — it decorates
    // with a decreasing index so ties break by position, and two clients disagreeing about WHICH
    // group dominates would print two different cautions from one set of rows.
    let dominant = "";
    let dominantCount = -1;
    let total = 0;
    for (const [group, count] of groupCounts) {
      total += count;
      if (count > dominantCount) {
        dominant = group;
        dominantCount = count;
      }
    }
    if (total >= MIN_SAMPLE_FOR_QUANTILES && dominantCount / total > 0.8) {
      cautions.push(
        `${dominantCount} of ${total} priced responses come from one respondent group (${dominant}). ` +
          `The price figures describe that group rather than the market.`
      );
    }
  }
  if (competitorValues.length && !respondentValues.length) {
    cautions.push(
      "Competitor prices were recorded but no buyer price expectations were. Shelf prices say what " +
        "is charged, not what the buyers surveyed said they would pay."
    );
  }

  return {
    observations: observations.length,
    respondentPrices: describe(respondentValues),
    competitorPrices: describe(competitorValues),
    byCategory,
    bands: bandVerdicts,
    competitors: positionCompetitors(competitors, observations),
    swot: linkSwotEvidence(swot, responses),
    clusters: clusterProducts(responses),
    groupCounts,
    cautions
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The wire form
 * ──────────────────────────────────────────────────────────────────────────── */

export type DistributionPayload = {
  count: number;
  minimum: number;
  maximum: number;
  mean: number;
  p25: number;
  median: number;
  p75: number;
  quantilesReported: boolean;
};

export type BandPayload = {
  category: string;
  declaredLow: number | null;
  declaredHigh: number | null;
  evidence: DistributionPayload | null;
  inside: number;
  below: number;
  above: number;
  coverage: number;
  /** One of {@link BandVerdictKind} — typed loosely because the server may know a verdict this build does not. */
  verdict: string;
  message: string;
};

export type CompetitorPayload = {
  name: string;
  seller: string;
  category: string;
  price: number;
  percentile: number | null;
  versusMedian: number | null;
  note: string;
};

export type SwotPayload = {
  kind: string;
  point: string;
  supported: boolean;
  hasOwnEvidence: boolean;
  supportedBy: string[];
  overlap: number;
};

export type ClusterPayload = { members: string[]; mentions: number; coOccurrences: number };

/**
 * Exactly what `GET /design-workshops/{id}/market-analysis` returns.
 *
 * `cautions` and `unsupportedSwot` are named at the TOP of the object rather than buried inside the
 * sections they qualify. A caution that has to be found is a caution that will not be read, and the
 * whole value of this analysis rests on a designer seeing "these figures come from one respondent
 * group" before they see the figures. The panel renders them in this order for the same reason.
 */
export type MarketFindingsPayload = {
  observations: number;
  cautions: string[];
  unsupportedSwot: { kind: string; point: string }[];
  respondentPrices: DistributionPayload | null;
  competitorPrices: DistributionPayload | null;
  byCategory: Record<string, DistributionPayload | null>;
  groupCounts: Record<string, number>;
  bands: BandPayload[];
  competitors: CompetitorPayload[];
  swot: SwotPayload[];
  clusters: ClusterPayload[];
};

function distributionPayload(dist: Distribution | null): DistributionPayload | null {
  if (!dist) return null;
  return {
    count: dist.count,
    minimum: pyRound(dist.minimum, 2),
    maximum: pyRound(dist.maximum, 2),
    mean: pyRound(dist.mean, 2),
    p25: pyRound(dist.p25, 2),
    median: pyRound(dist.median, 2),
    p75: pyRound(dist.p75, 2),
    quantilesReported: dist.quantilesReported
  };
}

/** The findings in the shape the endpoint returns, so one renderer serves both sources. */
export function marketFindingsPayload(findings: MarketFindings): MarketFindingsPayload {
  const byCategory: Record<string, DistributionPayload | null> = {};
  for (const category of [...findings.byCategory.keys()].sort(comparePyStrings)) {
    byCategory[category] = distributionPayload(findings.byCategory.get(category) ?? null);
  }
  const groupCounts: Record<string, number> = {};
  for (const group of [...findings.groupCounts.keys()].sort(comparePyStrings)) {
    groupCounts[group] = findings.groupCounts.get(group) as number;
  }
  return {
    observations: findings.observations,
    cautions: [...findings.cautions],
    unsupportedSwot: findings.swot
      .filter((point) => !point.supported)
      .map((point) => ({ kind: point.kind, point: point.point })),
    respondentPrices: distributionPayload(findings.respondentPrices),
    competitorPrices: distributionPayload(findings.competitorPrices),
    byCategory,
    groupCounts,
    bands: findings.bands.map((band) => ({
      category: band.category,
      declaredLow: band.declaredLow,
      declaredHigh: band.declaredHigh,
      evidence: distributionPayload(band.evidence),
      inside: band.inside,
      below: band.below,
      above: band.above,
      coverage: pyRound(band.coverage, 4),
      verdict: band.verdict,
      message: band.message
    })),
    competitors: findings.competitors.map((competitor) => ({
      name: competitor.name,
      seller: competitor.seller,
      category: competitor.category,
      price: competitor.price,
      percentile: competitor.percentile === null ? null : pyRound(competitor.percentile, 4),
      versusMedian: competitor.versusMedian === null ? null : pyRound(competitor.versusMedian, 2),
      note: competitor.note
    })),
    swot: findings.swot.map((point) => ({
      kind: point.kind,
      point: point.point,
      supported: point.supported,
      hasOwnEvidence: point.hasOwnEvidence,
      supportedBy: [...point.supportedBy],
      overlap: point.overlap
    })),
    clusters: findings.clusters.map((cluster) => ({
      members: [...cluster.members],
      mentions: cluster.mentions,
      coOccurrences: cluster.coOccurrences
    }))
  };
}

/** The findings for one workshop's stage-8 and stage-9 rows, in the endpoint's shape. */
export function analysePayload(input: {
  responses: MarketRow[];
  competitors: MarketRow[];
  bands: MarketRow[];
  swot: MarketRow[];
}): MarketFindingsPayload {
  return marketFindingsPayload(analyse(input));
}
