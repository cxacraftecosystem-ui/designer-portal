"use client";

import { countNoun } from "@/lib/format";
import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, Calculator, Info } from "lucide-react";

import { dwCostIntegrity, type DwRow } from "@/lib/designWorkshops";
import { isLocalWorkshopId, loadDraft } from "@/lib/designWorkshopStore";
import {
  analyseCostIntegrity,
  unsyncedSheetCount,
  type CostCheckPayload,
  type CostFindingsPayload,
  type CostRow
} from "@/lib/costIntegrity";
import { usableCheck } from "@/lib/stageAssist";

export const COSTING_STAGE = "COSTING_MARKET_LINKAGE";

const FINAL_PRODUCTS_STAGE = "FINAL_PROTOTYPE_DOCUMENTATION";

const NO_ROWS: DwRow[] = [];

export function CostFindingsPanel({
  workshopId,
  collections,
  onUse,
  disabled = false
}: {
  workshopId: string;
  collections: Record<string, DwRow[]>;
  onUse?: (sheetEntryId: string, key: string, value: string) => void;
  disabled?: boolean;
}) {
  const sheets = useMemo(() => collections.costSheet ?? NO_ROWS, [collections]);
  const materialLines = useMemo(() => collections.costMaterialLine ?? NO_ROWS, [collections]);
  const labourLines = useMemo(() => collections.costLabourLine ?? NO_ROWS, [collections]);

  const [labels, setLabels] = useState<Record<string, string>>({});
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const draft = await loadDraft(workshopId);
        if (cancelled) return;
        const products = (draft?.stages[FINAL_PRODUCTS_STAGE]?.collections.finalProduct ?? []) as CostRow[];
        const resolved: Record<string, string> = {};
        for (const row of products) {
          const id = typeof row._entryId === "string" ? row._entryId : "";
          const name = String(row.name ?? row.productCode ?? "").trim();
          if (id && name) resolved[id] = name;
        }
        setLabels(resolved);
      } catch {
        if (!cancelled) setLabels({});
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [workshopId]);

  const holdsNothing = !sheets.length && !materialLines.length && !labourLines.length;
  const [remote, setRemote] = useState<CostFindingsPayload | null>(null);
  const needsServer = holdsNothing && !isLocalWorkshopId(workshopId);
  useEffect(() => {
    if (!needsServer) return;
    let cancelled = false;
    void (async () => {
      try {
        const payload = await dwCostIntegrity(workshopId);
        if (!cancelled) setRemote(payload);
      } catch {
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [needsServer, workshopId]);

  const local = useMemo(
    () =>
      holdsNothing
        ? null
        : analyseCostIntegrity({
            sheets: sheets as CostRow[],
            materialLines: materialLines as CostRow[],
            labourLines: labourLines as CostRow[],
            labels
          }),
    [holdsNothing, sheets, materialLines, labourLines, labels]
  );

  const findings = local ?? remote;
  const source: "local" | "server" = local ? "local" : "server";
  const unsynced = unsyncedSheetCount(sheets as CostRow[]);

  if (!findings || (!findings.sheets.length && !findings.orphans.length)) return null;

  return (
    <section className="panel mb-5 grid gap-4 p-4" aria-labelledby="cost-findings-heading">
      <h2 id="cost-findings-heading" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
        <Calculator className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
        Cost sheets against their own lines
      </h2>

      {unsynced ? (
        <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          {unsynced} of {sheets.length} cost sheet{sheets.length === 1 ? "" : "s"} not uploaded yet, so {unsynced === 1 ? "its" : "their"} lines
          aren&rsquo;t in the subtotals below.
        </p>
      ) : null}

      {findings.warnings.length ? (
        <div className="grid gap-1.5 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-amber-800">
          <p className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide">
            <AlertTriangle className="h-3.5 w-3.5" aria-hidden />
            {findings.warnings.length} sheet figure{findings.warnings.length === 1 ? "" : "s"} the lines do not support
          </p>
          <ul className="grid gap-1 text-sm leading-6">
            {findings.warnings.map((warning) => (
              <li key={warning}>{warning}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {!unsynced && findings.cautions.length ? (
        <div className="grid gap-1.5 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-amber-800">
          <p className="text-xs font-semibold uppercase tracking-wide">Lines that belong to no sheet</p>
          <ul className="grid gap-1 text-sm leading-6">
            {findings.cautions.map((caution) => (
              <li key={caution}>{caution}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <p className="text-xs leading-5 text-ink-500">
        {source === "local"
          ? `From ${countNoun(findings.sheetCount, "sheet")}, ${materialLines.length} material and ${countNoun(labourLines.length, "labour line")}. Not saved to the stage.`
          : "From saved data only. Unsaved edits aren't counted."}
      </p>

      <div className="grid gap-3">
        {findings.sheets.map((sheet, index) => (
          <div key={sheet.entryId || `${sheet.label}-${index}`} className="grid gap-1.5">
            <h3 className="text-sm font-medium text-ink-900">{sheet.label}</h3>
            <ul className="grid gap-1.5">
              {sheet.checks.map((check) => (
                <li key={check.key} className="flex flex-wrap items-start gap-2">
                  <VerdictChip verdict={check.verdict} />
                  <span className="min-w-0 flex-1 text-sm leading-6 text-ink-700">{check.message}</span>
                  {onUse && source === "local" && sheet.entryId && usableCheck(check) ? (
                    <button
                      type="button"
                      className="field-button-secondary min-h-9 px-3 py-1 text-xs"
                      disabled={disabled}
                      onClick={() => onUse(sheet.entryId, check.key, (check.computed as number).toFixed(2))}
                    >
                      Use {formatRupees(check.computed as number)}
                    </button>
                  ) : null}
                </li>
              ))}
              <li className="flex flex-wrap items-start gap-2">
                <VerdictChip verdict={sheet.margin.verdict} />
                <span className="min-w-0 flex-1 text-sm leading-6 text-ink-700">{sheet.margin.message}</span>
              </li>
            </ul>
          </div>
        ))}
      </div>
    </section>
  );
}

function formatRupees(value: number): string {
  return `₹${value.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function VerdictChip({ verdict }: { verdict: CostCheckPayload["verdict"] }) {
  const bad = verdict === "MISMATCH" || verdict === "BELOW_COST";
  const good = verdict === "AGREES" || verdict === "COMPUTED";
  const tone = bad ? "bg-amber-100 text-amber-800" : good ? "bg-success-100 text-success-600" : "bg-field-100 text-ink-500";
  const label =
    verdict === "AGREES"
      ? "Matches the lines"
      : verdict === "MISMATCH"
        ? "Contradicts the lines"
        : verdict === "NOT_ITEMISED"
          ? "Nothing to check against"
          : verdict === "NOT_DECLARED"
            ? "Not declared"
            : verdict === "INCOMPLETE"
              ? "Lines could not be read"
              : verdict === "NOT_COMPUTABLE"
                ? "Cannot be worked out"
                : verdict === "COMPUTED"
                  ? "Margin"
                  : verdict === "AT_COST"
                    ? "Earns nothing"
                    : verdict === "BELOW_COST"
                      ? "Priced below cost"
                      : verdict;
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${tone}`}
    >
      {bad ? <AlertTriangle className="h-3.5 w-3.5" aria-hidden /> : <Info className="h-3.5 w-3.5" aria-hidden />}
      {label}
    </span>
  );
}
