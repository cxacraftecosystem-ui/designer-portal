import { byCount } from "@/lib/format";
import { asNumber, pyRound, pyStrip, pySum } from "@/lib/marketAnalysis";

export type CostRow = Record<string, unknown>;

export const COST_HEADS = [
  "materialCost",
  "labourCost",
  "packagingCost",
  "finishingCost",
  "transportCost",
  "overheadCost"
] as const;

const COST_HEAD_LABELS: Record<string, string> = {
  materialCost: "Material cost",
  labourCost: "Labour cost",
  packagingCost: "Packaging",
  finishingCost: "Finishing",
  transportCost: "Transport",
  overheadCost: "Overhead"
};

export const TOLERANCE_RUPEES = 1.0;

export const MARGIN_TOLERANCE_POINTS = 1.0;

function money(value: unknown): number | null {
  return asNumber(value);
}

function linesAdd(count: number, noun: string): string {
  return count === 1 ? `${count} ${noun} line adds` : `${count} ${noun} lines add`;
}

function rupees(value: number): string {
  if (Number.isNaN(value)) return "₹nan";
  if (!Number.isFinite(value)) return value > 0 ? "₹inf" : "₹-inf";
  const negative = value < 0 || Object.is(value, -0);
  const body = pyRound(Math.abs(value), 2).toFixed(2);
  const dot = body.indexOf(".");
  const whole = body.slice(0, dot);
  const fraction = body.slice(dot);
  return `₹${negative ? "-" : ""}${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}${fraction}`;
}

function percent1(value: number): string {
  if (Number.isNaN(value)) return "nan";
  if (!Number.isFinite(value)) return value > 0 ? "inf" : "-inf";
  const negative = value < 0 || Object.is(value, -0);
  return `${negative ? "-" : ""}${pyRound(Math.abs(value), 1).toFixed(1)}`;
}

function round(value: number | null, places = 2): number | null {
  return value === null ? null : pyRound(value, places);
}

export type LineRollUp = {
  kind: "MATERIAL" | "LABOUR";
  count: number;
  readable: number;
  total: number;
  unreadable: string[];
};

export function rollUp(kind: "MATERIAL" | "LABOUR", lines: CostRow[]): LineRollUp {
  const labelKey = kind === "MATERIAL" ? "item" : "task";
  let total = 0;
  let readable = 0;
  const unreadable: string[] = [];
  for (const line of lines) {
    const amount = money(line.amount);
    if (amount === null) {
      unreadable.push(pyStrip(String(line[labelKey] ?? "")) || "an unnamed line");
      continue;
    }
    total += amount;
    readable += 1;
  }
  return { kind, count: lines.length, readable, total, unreadable };
}

export type CostCheckPayload = {
  key: string;
  unit: "INR" | "PCT";
  declared: number | null;
  computed: number | null;
  difference: number | null;
  verdict: string;
  message: string;
  lineCount: number;
};

export function isCostFinding(check: CostCheckPayload): boolean {
  return check.verdict === "MISMATCH";
}

function compare(declared: number | null, computed: number | null, tolerance: number): string {
  if (declared === null || computed === null) return "NOT_DECLARED";
  return Math.abs(declared - computed) <= tolerance ? "AGREES" : "MISMATCH";
}

export function checkSubtotal(
  sheetLabel: string,
  key: string,
  noun: string,
  declaredRaw: unknown,
  rolled: LineRollUp
): CostCheckPayload {
  const declared = money(declaredRaw);

  if (rolled.count === 0) {
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
      `${sheetLabel}: the ${rolled.count} ${noun} ${byCount(rolled.count, "line adds", "lines add")} up to ${rupees(rolled.total)}; no ` +
      `${noun} cost is declared on the sheet.`;
    return { key, unit: "INR", declared: null, computed: rolled.total, difference: null, verdict: "NOT_DECLARED", message, lineCount: rolled.count };
  }

  const difference = declared - rolled.total;
  const verdict = compare(declared, rolled.total, TOLERANCE_RUPEES);
  const message =
    verdict === "AGREES"
      ? `${sheetLabel}: the ${rolled.count} ${noun} ${byCount(rolled.count, "line adds", "lines add")} up to ${rupees(rolled.total)}, ` +
        `which is what the sheet declares.`
      : `${sheetLabel}: the ${rolled.count} ${noun} ${byCount(rolled.count, "line adds", "lines add")} up to ${rupees(rolled.total)}, but ` +
        `the sheet declares ${rupees(declared)} — ${rupees(Math.abs(difference))} ` +
        `${difference < 0 ? "less" : "more"} than the lines account for.`;
  return { key, unit: "INR", declared, computed: rolled.total, difference, verdict, message, lineCount: rolled.count };
}

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

export type CostMarginPayload = {
  totalCost: number | null;
  expectedPrice: number | null;
  amount: number | null;
  percent: number | null;
  verdict: string;
  message: string;
};

export function computeMargin(sheetLabel: string, sheet: CostRow): CostMarginPayload {
  const { total: computed, unreadable } = sumCostHeads(sheet);
  let cost = unreadable.length ? null : computed;
  if (cost === null) cost = money(sheet.totalCost);
  const price = money(sheet.expectedPrice);

  if (cost === null || price === null || cost <= 0) {
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

export type CostOrphanPayload = { kind: string; label: string; ref: string; amount: number | null };

export type CostSheetPayload = {
  entryId: string;
  label: string;
  materialLines: number;
  labourLines: number;
  checks: CostCheckPayload[];
  margin: CostMarginPayload;
};

export type CostFindingsPayload = {
  sheetCount: number;
  warnings: string[];
  cautions: string[];
  sheets: CostSheetPayload[];
  orphans: CostOrphanPayload[];
};

function sheetLabelFor(sheet: CostRow, index: number, labels: Record<string, string>): string {
  const ref = sheet.productRef;
  if (typeof ref === "string" && ref) {
    const label = labels[ref] || "";
    if (label) return label;
  }
  return `Cost sheet ${index}`;
}

function orphanCaution(noun: string, orphans: CostOrphanPayload[]): string {
  const named = orphans
    .slice(0, 3)
    .map((orphan) => orphan.label)
    .filter(Boolean)
    .join(", ");
  const amounts = orphans.map((orphan) => orphan.amount).filter((amount): amount is number => amount !== null);
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

  const warnings: string[] = [];
  for (const sheet of found) {
    for (const check of sheet.checks) if (isCostFinding(check)) warnings.push(check.message);
    if (sheet.margin.verdict === "BELOW_COST") warnings.push(sheet.margin.message);
  }

  return { sheetCount: found.length, warnings, cautions, sheets: found, orphans };
}

export function unsyncedSheetCount(sheets: CostRow[]): number {
  return sheets.filter((sheet) => !(typeof sheet._entryId === "string" && sheet._entryId)).length;
}
