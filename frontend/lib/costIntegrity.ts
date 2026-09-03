/**
 * Stage 17's cost sheets checked against their own line items, in the browser — the port of
 * `backend/app/services/cost_integrity.py`.
 *
 * ── WHAT IT CATCHES ───────────────────────────────────────────────────────────────────────────
 * A cost sheet carries a header (material cost, labour cost, four more heads and a total) and,
 * underneath it, the material and labour LINES those heads are supposed to summarise. Nothing ever
 * compared the two. A designer hand-totals six material lines, retypes ₹1,650.00 as ₹1,560.00, and
 * the sheet now contradicts itself — and the header is what the report prints into a document
 * submitted to a Development Commissioner's office, with the lines that disprove it two tables
 * below on the same page.
 *
 * The registry cannot express the check and that is not an oversight to route around:
 * `stage_schema.derive_value` takes `(spec, row)` and reads only the SAME row's other fields, so a
 * roll-up across a sibling collection is out of its reach by construction.
 *
 * ── WHY A PORT AND NOT A FETCH ────────────────────────────────────────────────────────────────
 * The Python module is pure on purpose — no database, no network — precisely so the same arithmetic
 * can run on the server, on the handset and here. The Kotlin port (`DwCostIntegrity.kt`) has existed
 * and been surfaced on the handset for some time; **the browser had neither a port nor a panel, so
 * a designer filling stage 17 in a browser was the one person the check never reached**, while the
 * same designer on the handset was warned about the same workshop. `GET
 * /design-workshops/{id}/cost-integrity` is the fallback for a device that has never downloaded the
 * stage, not the source.
 *
 * ── IT IS A PORT, NOT A SECOND OPINION ────────────────────────────────────────────────────────
 * When this file and `cost_integrity.py` disagree, the Python is right and this is broken. Two
 * things make disagreement easy and are therefore not delegated:
 *
 *  - **Money formatting.** Python's `format(x, ',.2f')` rounds half to EVEN; JavaScript's `toFixed`
 *    rounds half away from zero. Every figure here goes through {@link pyRound}, imported from
 *    `marketAnalysis` rather than re-implemented, exactly as the Python imports `as_number` from
 *    `market_analysis` rather than copying it.
 *  - **Reading a stored MONEY value.** It is a fixed-2 string that may carry grouping commas, and
 *    blank is null rather than zero. {@link asNumber} is the one implementation, shared.
 *
 * Summation is where the two spellings really are different things and the file uses BOTH. The
 * roll-ups and the cost heads are Python `total += value`, a plain running total, so they are plain
 * `+=` here. The ORPHAN caution's figure is Python's builtin `sum()`, which since CPython 3.12 is
 * the compensated Neumaier algorithm — so that one, and only that one, goes through {@link pySum}.
 * Using either spelling in the other's place produces a different sentence; see the note on
 * `orphanCaution` for the fuzz case that found it.
 *
 * ── IT PRODUCES A FINDING. IT DOES NOT CORRECT ANYTHING ───────────────────────────────────────
 * Nothing here returns anything a form should be seeded from. A subtotal can legitimately differ
 * from its lines — a rounding, a cost carried but not itemised, a rate renegotiated after the lines
 * were entered — and the designer was in the room when it was decided. What they did not have was
 * the arithmetic in front of them.
 *
 * ── IT REFUSES TO CONCLUDE WHEN IT CANNOT ─────────────────────────────────────────────────────
 * A sheet with no lines is NOT_ITEMISED, not a contradiction. A sheet whose lines cannot all be read
 * is INCOMPLETE rather than totalled from the readable half — a partial total compared against a
 * correct header manufactures a mismatch that is not there, and a check that accuses correct sheets
 * is a check that gets ignored, taking the true findings with it. A line naming no sheet is an
 * ORPHAN and is reported with its amount, because it is fieldwork somebody did and money somebody
 * spent, and its absence from every subtotal may be the very discrepancy being looked at.
 */

import { asNumber, pyRound, pyStrip, pySum } from "@/lib/marketAnalysis";

/** One stored stage row's `data`, with `_entryId` injected by whoever loaded it. */
export type CostRow = Record<string, unknown>;

/**
 * The six heads `totalCost` is declared to SUM over, in `stage_definitions.STAGE_17`.
 *
 * Re-stated rather than read out of the registry, exactly as the Python re-states them, so this
 * module stays free of it and there is one list for all three ports to mirror.
 * `test_the_six_cost_heads_match_the_registry_declaration` pins the Python to the registry, so a
 * head added there fails a test rather than being silently left out of the roll-up.
 */
export const COST_HEADS = [
  "materialCost",
  "labourCost",
  "packagingCost",
  "finishingCost",
  "transportCost",
  "overheadCost"
] as const;

/** The heads by the label the designer sees, so a message can say which one it could not read. */
const COST_HEAD_LABELS: Record<string, string> = {
  materialCost: "Material cost",
  labourCost: "Labour cost",
  packagingCost: "Packaging",
  finishingCost: "Finishing",
  transportCost: "Transport",
  overheadCost: "Overhead"
};

/**
 * How far a declared subtotal may sit from its lines before it is called a contradiction: ONE RUPEE.
 *
 * There is no float drift to absorb — MONEY is stored as a fixed-2 string and every line `amount` is
 * already rounded to the paisa — so the tolerance is not for the machine's arithmetic. It is for the
 * designer's: someone who totals ₹1,649.50 of yarn and writes ₹1,650 has rounded to the rupee, which
 * is how a cost sheet is normally quoted, and that is not a sheet contradicting itself. It is also
 * comfortably below every failure this exists to catch — a transposed digit is ₹90, a misplaced
 * decimal is ten times the sheet, an unadded line is its whole amount. Nothing real lands between ₹1
 * and ₹10, and a warning that fires on correct data is one designers learn to dismiss.
 */
export const TOLERANCE_RUPEES = 1.0;

/**
 * How far a declared `marginPercent` may sit from the margin the sheet's own figures imply: ONE
 * PERCENTAGE POINT. A designer who computes 25.4% and types 25 has rounded; two points apart is a
 * different claim about the same product.
 */
export const MARGIN_TOLERANCE_POINTS = 1.0;

/** A stored MONEY value as a finite number, or null. Blank is null, not zero. */
function money(value: unknown): number | null {
  return asNumber(value);
}

/**
 * A figure as a message prints it — Python's `f"₹{value:,.2f}"`.
 *
 * TO THE PAISA, deliberately, unlike `marketAnalysis`' whole-rupee price bands: the tolerance here
 * is a rupee, so a message that rounded to the rupee could report a ₹1.40 discrepancy as
 * "₹1,650 against ₹1,650" and read as though the check had malfunctioned.
 *
 * Half-to-even through `pyRound`, and hand-grouped rather than through `Intl.NumberFormat`, whose
 * default rounding is `halfExpand` and which groups 100000 as 1,00,000 under an Indian locale while
 * the server prints 100,000. Two spellings of one figure in one panel read as two figures.
 */
function rupees(value: number): string {
  // Python spells the three non-finite floats "inf", "-inf" and "nan"; JavaScript spells them
  // "Infinity", "-Infinity" and "NaN". A caution that overflows is a real case — see the orphan
  // total below and the `k28-orphan-total-overflows` fixture — and the two surfaces must print the
  // same sentence for it, however unlikely a designer is to meet one.
  if (Number.isNaN(value)) return "₹nan";
  if (!Number.isFinite(value)) return value > 0 ? "₹inf" : "₹-inf";
  const negative = value < 0 || Object.is(value, -0);
  const body = pyRound(Math.abs(value), 2).toFixed(2);
  const dot = body.indexOf(".");
  const whole = body.slice(0, dot);
  const fraction = body.slice(dot);
  return `₹${negative ? "-" : ""}${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}${fraction}`;
}

/** Python's `f"{value:.1f}"` — half to even, no grouping. Percentages only. */
function percent1(value: number): string {
  // The same three spellings as {@link rupees}, for the same reason.
  if (Number.isNaN(value)) return "nan";
  if (!Number.isFinite(value)) return value > 0 ? "inf" : "-inf";
  const negative = value < 0 || Object.is(value, -0);
  return `${negative ? "-" : ""}${pyRound(Math.abs(value), 1).toFixed(1)}`;
}

/** `_round` on the wire: every figure the payload carries is rounded exactly as the server rounds it. */
function round(value: number | null, places = 2): number | null {
  return value === null ? null : pyRound(value, places);
}

/* ────────────────────────────────────────────────────────────────────────────
 * What the lines under one sheet come to
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What one sheet's child lines of a single kind add up to, and what could not be read.
 *
 * `unreadable` is separate from `count` on purpose: a total computed from the readable lines alone
 * looks exactly like a total computed from all of them, and the difference is the difference between
 * a finding and a false accusation.
 */
export type LineRollUp = {
  kind: "MATERIAL" | "LABOUR";
  count: number;
  readable: number;
  total: number;
  unreadable: string[];
};

/**
 * Total the `amount` column of `lines`, keeping the unreadable ones visible.
 *
 * The STORED `amount` is used rather than recomputed from quantity × rate: it is the registry's own
 * PRODUCT derivation, written on save by all three clients, and re-deriving it here would put a
 * second implementation of the same arithmetic in the codebase — the drift then shows up as a
 * roll-up that disagrees with the Amount column printed directly above it in the report.
 */
export function rollUp(kind: "MATERIAL" | "LABOUR", lines: CostRow[]): LineRollUp {
  const labelKey = kind === "MATERIAL" ? "item" : "task";
  let total = 0;
  let readable = 0;
  const unreadable: string[] = [];
  for (const line of lines) {
    const amount = money(line.amount);
    if (amount === null) {
      // `pyStrip` and not `trim()`: `cost_integrity.py:166` is `.strip()` and `DwCostIntegrity.kt`
      // is `DwPy.strip`, and the two sets differ over U+0085 and U+FEFF (2026-09-03). A label padded
      // with a next-line reads as its real name there and as "an unnamed line" here.
      unreadable.push(pyStrip(String(line[labelKey] ?? "")) || "an unnamed line");
      continue;
    }
    total += amount;
    readable += 1;
  }
  return { kind, count: lines.length, readable, total, unreadable };
}

/* ────────────────────────────────────────────────────────────────────────────
 * One declared figure against what the sheet's own rows say it should be
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One comparison between a figure a designer typed and the arithmetic underneath it.
 *
 * `verdict` is one of:
 *   AGREES          within tolerance of what the rows come to
 *   MISMATCH        outside it — the one verdict that becomes a warning
 *   NOT_ITEMISED    there are no rows to roll up. NOT a criticism of the sheet
 *   NOT_DECLARED    nothing was typed to compare against; the computed figure is reported anyway
 *   INCOMPLETE      rows exist but could not all be read, so no total was formed
 *   NOT_COMPUTABLE  the computed side cannot be formed at all (a margin with no cost)
 *
 * The union is left open at `string` deliberately, exactly as the market panel's verdict chip is: a
 * server running ahead of this build may name a verdict this file has never heard of, and an unnamed
 * finding is still a finding.
 */
export type CostCheckPayload = {
  key: string;
  unit: "INR" | "PCT";
  declared: number | null;
  computed: number | null;
  /** declared − computed. */
  difference: number | null;
  verdict: string;
  message: string;
  lineCount: number;
};

/** True for the one verdict that is a finding to act on. */
export function isCostFinding(check: CostCheckPayload): boolean {
  return check.verdict === "MISMATCH";
}

/**
 * AGREES or MISMATCH for two figures that both exist.
 *
 * The comparison is `<=` so a difference EXACTLY at the tolerance still agrees — the boundary
 * belongs to the designer, not to the warning.
 */
function compare(declared: number | null, computed: number | null, tolerance: number): string {
  if (declared === null || computed === null) return "NOT_DECLARED";
  return Math.abs(declared - computed) <= tolerance ? "AGREES" : "MISMATCH";
}

/**
 * One declared subtotal against the lines it summarises.
 *
 * The order of the refusals is the order of the questions. Are there rows at all? Could they all be
 * read? Was anything typed to compare them with? Only then is a mismatch a mismatch.
 */
export function checkSubtotal(
  sheetLabel: string,
  key: string,
  noun: string,
  declaredRaw: unknown,
  rolled: LineRollUp
): CostCheckPayload {
  const declared = money(declaredRaw);

  if (rolled.count === 0) {
    // AN UN-ITEMISED SHEET IS NOT A WRONG SHEET, and this is the case the whole module has to get
    // right. Plenty of sheets are entered as totals — a subcontracted product, a cost carried over
    // from a previous workshop — and reporting every one of them as a contradiction would bury the
    // sheets that really are one.
    const message =
      declared === null
        ? `${sheetLabel}: no ${noun} lines are recorded and no ${noun} cost is declared.`
        : `${sheetLabel}: ${rupees(declared)} is declared as ${noun} cost and there are no ${noun} ` +
          `lines to check it against. An un-itemised subtotal is not an error — it simply cannot be ` +
          `verified from this stage.`;
    return { key, unit: "INR", declared, computed: null, difference: null, verdict: "NOT_ITEMISED", message, lineCount: 0 };
  }

  if (rolled.unreadable.length) {
    const named = rolled.unreadable.slice(0, 3).join(", ");
    const message =
      `${sheetLabel}: ${rolled.unreadable.length} of ${rolled.count} ${noun} lines have no readable ` +
      `amount (${named}), so the lines cannot be totalled and the declared subtotal cannot be ` +
      `checked. Reopening those lines and saving them will recompute the Amount column.`;
    return { key, unit: "INR", declared, computed: null, difference: null, verdict: "INCOMPLETE", message, lineCount: rolled.count };
  }

  if (declared === null) {
    const message =
      `${sheetLabel}: the ${rolled.count} ${noun} line(s) add up to ${rupees(rolled.total)}; no ` +
      `${noun} cost is declared on the sheet.`;
    return { key, unit: "INR", declared: null, computed: rolled.total, difference: null, verdict: "NOT_DECLARED", message, lineCount: rolled.count };
  }

  const difference = declared - rolled.total;
  const verdict = compare(declared, rolled.total, TOLERANCE_RUPEES);
  const message =
    verdict === "AGREES"
      ? `${sheetLabel}: the ${rolled.count} ${noun} line(s) add up to ${rupees(rolled.total)}, ` +
        `which is what the sheet declares.`
      : `${sheetLabel}: the ${rolled.count} ${noun} line(s) add up to ${rupees(rolled.total)}, but ` +
        `the sheet declares ${rupees(declared)} — ${rupees(Math.abs(difference))} ` +
        `${difference < 0 ? "less" : "more"} than the lines account for.`;
  return { key, unit: "INR", declared, computed: rolled.total, difference, verdict, message, lineCount: rolled.count };
}

/**
 * The six heads added up, and the labels of any that could not be read.
 *
 * MIRRORS `derive_value`'s SUM EXACTLY, and the two rules that make it non-obvious are the reason it
 * is spelled out. A blank head counts as ZERO — four of the six are optional and requiring all six
 * would leave `totalCost` empty for most workshops. But a sheet with NONE of them filled has no
 * total at all rather than a total of zero, because "₹ 0.00" in a cost sheet a ministry reads is a
 * claim and not a blank.
 */
export function sumCostHeads(sheet: CostRow): { total: number | null; unreadable: string[] } {
  let total = 0;
  let seen = false;
  const unreadable: string[] = [];
  for (const key of COST_HEADS) {
    const raw = sheet[key];
    if (raw === null || raw === undefined || raw === "") continue;
    const value = money(raw);
    if (value === null) {
      unreadable.push(COST_HEAD_LABELS[key]);
      continue;
    }
    total += value;
    seen = true;
  }
  return { total: seen ? total : null, unreadable };
}

/**
 * The stored `totalCost` against the six heads it is derived from.
 *
 * UNLIKE THE SUBTOTALS, THIS ONE IS A DERIVED FIELD, so a mismatch means something narrower and more
 * actionable: the stored value is STALE. It was computed correctly from what the sheet held at the
 * time and a head has moved since — most often on a row written by a client running an older copy of
 * the registry, or edited while offline. The fix is mechanical and the message says so, which is not
 * true of a subtotal mismatch where only the designer can know the answer.
 */
export function checkTotalCost(sheetLabel: string, sheet: CostRow): CostCheckPayload {
  const { total: computed, unreadable } = sumCostHeads(sheet);
  const declared = money(sheet.totalCost);

  if (unreadable.length) {
    const message =
      `${sheetLabel}: ${unreadable.join(", ")} could not be read as a number, so the total cost ` +
      `cannot be checked against the cost heads.`;
    return { key: "totalCost", unit: "INR", declared, computed: null, difference: null, verdict: "INCOMPLETE", message, lineCount: 0 };
  }

  if (computed === null) {
    const message = `${sheetLabel}: none of the six cost heads is filled in, so there is no total to check.`;
    return { key: "totalCost", unit: "INR", declared, computed: null, difference: null, verdict: "NOT_ITEMISED", message, lineCount: 0 };
  }

  if (declared === null) {
    const message = `${sheetLabel}: the cost heads add up to ${rupees(computed)}; no total cost is stored on the sheet.`;
    return { key: "totalCost", unit: "INR", declared: null, computed, difference: null, verdict: "NOT_DECLARED", message, lineCount: 0 };
  }

  const difference = declared - computed;
  const verdict = compare(declared, computed, TOLERANCE_RUPEES);
  const message =
    verdict === "AGREES"
      ? `${sheetLabel}: the stored total cost ${rupees(declared)} matches the six cost heads.`
      : `${sheetLabel}: the six cost heads add up to ${rupees(computed)}, but the stored total cost ` +
        `is ${rupees(declared)}. Total cost is a derived field, so the stored value is out of date — ` +
        `reopening the sheet and saving it will recompute it.`;
  return { key: "totalCost", unit: "INR", declared, computed, difference, verdict, message, lineCount: 0 };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Margin
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What the sheet's own figures imply about what it earns.
 *
 * `percent` is margin ON COST — `(price - cost) / cost` — and saying which convention is meant is not
 * pedantry: the same sheet is 25% on cost and 20% on price, and the registry's own `marginPercent`
 * allows up to 500, which only a markup on cost can reach.
 *
 * `verdict` is one of COMPUTED, AT_COST, BELOW_COST, NOT_COMPUTABLE. Only BELOW_COST is a finding.
 */
export type CostMarginPayload = {
  totalCost: number | null;
  expectedPrice: number | null;
  amount: number | null;
  percent: number | null;
  verdict: string;
  message: string;
};

/**
 * The margin between `expectedPrice` and the sheet's cost.
 *
 * COMPUTED FROM THE SIX HEADS RATHER THAN THE STORED `totalCost`, falling back to the stored value
 * only when no head is filled. If the stored total is stale — which {@link checkTotalCost} may have
 * just reported — a margin taken from it would be a second wrong number derived from the first, and
 * the two findings would contradict each other on the same sheet.
 */
export function computeMargin(sheetLabel: string, sheet: CostRow): CostMarginPayload {
  const { total: computed, unreadable } = sumCostHeads(sheet);
  let cost = unreadable.length ? null : computed;
  if (cost === null) cost = money(sheet.totalCost);
  const price = money(sheet.expectedPrice);

  if (cost === null || price === null || cost <= 0) {
    // A cost of zero is not a free product, it is a sheet nobody has costed yet, and dividing by it
    // would either raise or report an infinite margin into a report.
    const reason =
      price === null && cost === null
        ? "neither an expected price nor any cost is recorded"
        : price === null
          ? "no expected price is recorded"
          : cost === null
            ? "no cost is recorded"
            : "the total cost is zero";
    return {
      totalCost: cost,
      expectedPrice: price,
      amount: null,
      percent: null,
      verdict: "NOT_COMPUTABLE",
      message: `${sheetLabel}: no margin can be implied — ${reason}.`
    };
  }

  const amount = price - cost;
  const percent = (amount / cost) * 100;

  if (Math.abs(amount) <= TOLERANCE_RUPEES) {
    return {
      totalCost: cost,
      expectedPrice: price,
      amount,
      percent,
      verdict: "AT_COST",
      message:
        `${sheetLabel}: the expected price ${rupees(price)} is the same as the total cost ` +
        `${rupees(cost)} — this product earns nothing as priced.`
    };
  }
  if (amount < 0) {
    return {
      totalCost: cost,
      expectedPrice: price,
      amount,
      percent,
      verdict: "BELOW_COST",
      message:
        `${sheetLabel}: the expected price ${rupees(price)} is below the total cost ${rupees(cost)} — ` +
        `a loss of ${rupees(Math.abs(amount))} on every piece sold.`
    };
  }
  return {
    totalCost: cost,
    expectedPrice: price,
    amount,
    percent,
    verdict: "COMPUTED",
    message:
      `${sheetLabel}: an expected price of ${rupees(price)} against a total cost of ${rupees(cost)} ` +
      `implies a margin of ${rupees(amount)}, or ${percent1(percent)}% on cost.`
  };
}

/**
 * The typed `marginPercent` against the margin the sheet's own figures imply.
 *
 * The same class of bug as a mistyped subtotal — a number a designer entered by hand that the rest of
 * the sheet contradicts — and nothing checked it before either.
 */
export function checkDeclaredMargin(sheetLabel: string, sheet: CostRow, margin: CostMarginPayload): CostCheckPayload {
  const declared = asNumber(sheet.marginPercent);

  if (margin.percent === null) {
    const message =
      declared === null
        ? `${sheetLabel}: there is no implied margin to check a declared margin against.`
        : `${sheetLabel}: a margin of ${percent1(declared)}% is declared, but the sheet has no price ` +
          `and cost to imply a margin from, so it cannot be checked.`;
    return { key: "marginPercent", unit: "PCT", declared, computed: null, difference: null, verdict: "NOT_COMPUTABLE", message, lineCount: 0 };
  }

  if (declared === null) {
    return {
      key: "marginPercent",
      unit: "PCT",
      declared: null,
      computed: margin.percent,
      difference: null,
      verdict: "NOT_DECLARED",
      message:
        `${sheetLabel}: the sheet's figures imply a margin of ${percent1(margin.percent)}% on cost; ` +
        `no margin is declared.`,
      lineCount: 0
    };
  }

  const difference = declared - margin.percent;
  const verdict = compare(declared, margin.percent, MARGIN_TOLERANCE_POINTS);
  const message =
    verdict === "AGREES"
      ? `${sheetLabel}: the declared margin of ${percent1(declared)}% matches the ` +
        `${percent1(margin.percent)}% implied by the price and cost.`
      : `${sheetLabel}: a margin of ${percent1(declared)}% is declared, but the expected price and ` +
        `total cost on this sheet imply ${percent1(margin.percent)}% on cost.`;
  return { key: "marginPercent", unit: "PCT", declared, computed: margin.percent, difference, verdict, message, lineCount: 0 };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Orphans, one sheet, and the whole stage
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A cost line that belongs to no sheet in this stage.
 *
 * `amount` is null when the line's own amount could not be read — the line is still reported, because
 * the point of reporting it is that it EXISTS and is in no total.
 */
export type CostOrphanPayload = { kind: string; label: string; ref: string; amount: number | null };

export type CostSheetPayload = {
  entryId: string;
  label: string;
  materialLines: number;
  labourLines: number;
  checks: CostCheckPayload[];
  margin: CostMarginPayload;
};

/**
 * The wire form, camelCased exactly like `cost_findings_payload`.
 *
 * `warnings` is named at the TOP rather than left to be assembled by each client from the per-check
 * verdicts. Three clients reading the same rules three ways is three chances to show a designer a
 * clean sheet that this module found a contradiction in.
 */
export type CostFindingsPayload = {
  sheetCount: number;
  warnings: string[];
  cautions: string[];
  sheets: CostSheetPayload[];
  orphans: CostOrphanPayload[];
};

/**
 * What a finding calls this sheet.
 *
 * The entity's `label_field` is `productRef`, a REF this pure module cannot resolve on its own — the
 * products it points at live in stage 16. The caller passes what it has resolved, and the fallback is
 * positional, which is the same answer `report_builder._row_label` gives a sheet that names no
 * product. A finding a designer cannot trace back to a row is one they cannot act on.
 */
function sheetLabelFor(sheet: CostRow, index: number, labels: Record<string, string>): string {
  const ref = sheet.productRef;
  if (typeof ref === "string" && ref) {
    const label = labels[ref] || "";
    if (label) return label;
  }
  return `Cost sheet ${index}`;
}

/** One sentence naming lines that are in no sheet, and the money that is therefore in no total. */
function orphanCaution(noun: string, orphans: CostOrphanPayload[]): string {
  const named = orphans
    .slice(0, 3)
    .map((orphan) => orphan.label)
    .filter(Boolean)
    .join(", ");
  const amounts = orphans.map((orphan) => orphan.amount).filter((amount): amount is number => amount !== null);
  /*
    THE COMPENSATED SUM, AND THIS IS THE ONE PLACE IN THIS FILE THAT USES IT.

    `cost_integrity` totals the roll-ups and the cost heads with `total += value` — a plain running
    total, which JavaScript's `+=` matches operation for operation. This line is different: it is
    Python's BUILTIN `sum()`, and since CPython 3.12 (which `backend/Dockerfile` pins) that is the
    improved Kahan-Babuška algorithm by Neumaier, not a running total. The two agree until a sample
    mixes magnitudes far enough apart that small values fall off the bottom of the accumulator and
    the extremes then cancel — at which point the server still holds the small ones and a naive
    total has already lost them.

    That is not a hypothetical: a 280-case differential fuzz between the Kotlin port and the Python
    failed on exactly this sentence, and the case is pinned as `k27-orphan-total-compensated-sum` —
    four orphan lines where the server says "₹2,560.00, counted in no subtotal" and a running total
    says ₹1,000.00. Orphans keep the order they were entered in, so the cancelling pair straddles
    the amounts it destroys.
  */
  const total = amounts.length
    ? ` Their amounts come to ${rupees(pySum(amounts))}, counted in no subtotal.`
    : " None of them has a readable amount.";
  const plural = orphans.length === 1 ? "line is" : "lines are";
  return (
    `${orphans.length} ${noun} ${plural} not attached to any cost sheet in this stage` +
    `${named ? ` (${named})` : ""}. The sheet they named may have been deleted after they were ` +
    `recorded.${total}`
  );
}

/**
 * Every stage-17 cost sheet checked against its own line items.
 *
 * Each argument is the list of stage entry `data` objects exactly as they are stored, with
 * `_entryId` present on each row — so a caller has nothing to reshape and can pass what it already
 * holds. `labels` maps an entry id to the name it should print under, for the `productRef` this
 * module cannot resolve itself; its absence costs a readable sheet name, never a wrong verdict.
 *
 * Returns the WIRE payload directly, so the panel renders one shape whether the findings were
 * computed here or fetched from `GET /design-workshops/{id}/cost-integrity`. The Python splits the
 * same work across `analyse_cost_integrity` and `cost_findings_payload`; there is nothing in the
 * browser that wants the un-rounded intermediate form, and one shape is one thing to get wrong.
 */
export function analyseCostIntegrity({
  sheets,
  materialLines,
  labourLines,
  labels = {}
}: {
  sheets: CostRow[];
  materialLines: CostRow[];
  labourLines: CostRow[];
  labels?: Record<string, string>;
}): CostFindingsPayload {
  /*
    Bucket the children by the parent id they name, exactly as `report_builder._parent_groups` does:
    an empty or missing ref goes to "" and is never matched to a sheet, because a sheet that has not
    synced yet has no `_entryId` either and pairing the two would attach lines to a sheet at random.

    `Map` rather than a plain object, so the orphan order below is insertion order the way Python's
    dict is — and so a `costSheetRef` that happens to spell `__proto__` cannot reach the prototype.
  */
  const materialBySheet = new Map<string, CostRow[]>();
  const labourBySheet = new Map<string, CostRow[]>();
  for (const [rows, bucket] of [
    [materialLines, materialBySheet],
    [labourLines, labourBySheet]
  ] as const) {
    for (const row of rows) {
      const ref = row.costSheetRef;
      const key = typeof ref === "string" && ref ? ref : "";
      const existing = bucket.get(key);
      if (existing) existing.push(row);
      else bucket.set(key, [row]);
    }
  }

  const found: CostSheetPayload[] = [];
  sheets.forEach((sheet, position) => {
    const rawId = sheet._entryId;
    const entryId = typeof rawId === "string" && rawId ? rawId : "";
    const label = sheetLabelFor(sheet, position + 1, labels);

    const takeFrom = (bucket: Map<string, CostRow[]>) => {
      if (!entryId) return [];
      const rows = bucket.get(entryId) ?? [];
      bucket.delete(entryId);
      return rows;
    };
    const material = rollUp("MATERIAL", takeFrom(materialBySheet));
    const labour = rollUp("LABOUR", takeFrom(labourBySheet));

    const margin = computeMargin(label, sheet);
    const checks = [
      checkSubtotal(label, "materialCost", "material", sheet.materialCost, material),
      checkSubtotal(label, "labourCost", "labour", sheet.labourCost, labour),
      checkTotalCost(label, sheet),
      checkDeclaredMargin(label, sheet, margin)
    ];
    found.push({
      entryId,
      label,
      materialLines: material.count,
      labourLines: labour.count,
      checks: checks.map((check) => ({
        ...check,
        declared: round(check.declared),
        computed: round(check.computed),
        difference: round(check.difference)
      })),
      margin: {
        ...margin,
        totalCost: round(margin.totalCost),
        expectedPrice: round(margin.expectedPrice),
        amount: round(margin.amount),
        percent: round(margin.percent, 1)
      }
    });
  });

  /*
    WHATEVER IS LEFT IN THE BUCKETS BELONGS TO NO SHEET. Reported, never dropped: these are lines
    somebody entered and money somebody spent, and their absence from every subtotal is a candidate
    explanation for any mismatch above.
  */
  const orphans: CostOrphanPayload[] = [];
  const cautions: string[] = [];
  for (const [kind, noun, bucket] of [
    ["MATERIAL", "material", materialBySheet],
    ["LABOUR", "labour", labourBySheet]
  ] as const) {
    const labelKey = kind === "MATERIAL" ? "item" : "task";
    const ofKind: CostOrphanPayload[] = [];
    for (const rows of bucket.values()) {
      for (const row of rows) {
        ofKind.push({
          kind,
          // `pyStrip` for the same reason as in `rollUp` above: `cost_integrity.py:671` strips the
          // Python set, and an orphan is NAMED in a caution an admin reads (2026-09-03).
          label: pyStrip(String(row[labelKey] ?? "")) || "an unnamed line",
          ref: String(row.costSheetRef ?? ""),
          amount: round(money(row.amount))
        });
      }
    }
    if (ofKind.length) {
      orphans.push(...ofKind);
      cautions.push(orphanCaution(`${noun} cost`, ofKind));
    }
  }

  /*
    DERIVED FROM THE CHECKS rather than accumulated alongside them, so the banner a designer reads
    and the per-field verdicts underneath it cannot disagree about the same sheet.
  */
  const warnings: string[] = [];
  for (const sheet of found) {
    for (const check of sheet.checks) if (isCostFinding(check)) warnings.push(check.message);
    if (sheet.margin.verdict === "BELOW_COST") warnings.push(sheet.margin.message);
  }

  return { sheetCount: found.length, warnings, cautions, sheets: found, orphans };
}

/**
 * Cost sheets on this screen that the server has never seen, counted.
 *
 * ── WHY THE PANEL NEEDS THIS ──────────────────────────────────────────────────────────────────
 * `costSheetRef` holds the sheet's `_entryId`, which is the SERVER's id, and the picker that fills
 * that field is served from the references endpoint — so a sheet created in a courtyard cannot be
 * offered to its own lines at all. The designer leaves the link blank because there is nothing to
 * pick, the lines land in the orphan bucket, and the sheet reads "un-itemised".
 *
 * COUNTED RATHER THAN ALL-OR-NOTHING. Every caution this module produces is an ORPHAN caution and
 * every orphan caution offers a deleted sheet as the explanation — which is the wrong explanation,
 * and an alarming one, the moment a sheet on this very screen is simply waiting for a tower. Telling
 * a designer their morning's costing has been deleted, in the one situation this app is built for, is
 * exactly the false accusation this module's design forbids, and a designer told it once stops
 * reading the amber for the rest of the workshop. The handset makes the same distinction with the
 * same count (`dwUnsyncedSheetCount`), and the port's own `cautions` stay byte-identical either way.
 *
 * A row whose `_entryId` is present but EMPTY counts as unsynced for the same reason
 * {@link analyseCostIntegrity} refuses to match on it: an empty string is not an identity, and
 * joining two rows on it attributes a week of costing at random.
 */
export function unsyncedSheetCount(sheets: CostRow[]): number {
  return sheets.filter((sheet) => !(typeof sheet._entryId === "string" && sheet._entryId)).length;
}
