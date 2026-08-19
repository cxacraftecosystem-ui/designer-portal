"use client";

/**
 * COST SHEETS AGAINST THEIR OWN LINES — stage 17's declared figures, held up against the material
 * and labour rows underneath them.
 *
 * ── THE PARITY GAP THIS CLOSES ────────────────────────────────────────────────────────────────
 * A cost sheet can contradict ITSELF: six material lines add up to ₹1,650.00 and the header declares
 * ₹1,560.00, and the header is what the report prints into a document submitted to a Development
 * Commissioner's office. `cost_integrity.py` has done that arithmetic for some time, `DwCostIntegrity
 * .kt` ports it and `DwFindingsPanel.kt` shows it on every handset — and the browser had NEITHER a
 * port nor a panel. `grep -rn "cost-integrity|costIntegrity|CostIntegrity" frontend` returned zero.
 * So the two clients disagreed about the same workshop, and the web — where a cost sheet is most
 * likely to actually be typed, on a laptop with a keyboard — was the surface where a
 * self-contradicting sheet reached an officer unchallenged.
 *
 * ── FOUR RULES, EACH LOAD-BEARING ─────────────────────────────────────────────────────────────
 *
 * 1. **IT COMPUTES ON THIS DEVICE.** `lib/costIntegrity.ts` is a port of the same pure arithmetic, so
 *    the findings come from the rows on this page and appear with no network at all. That is the
 *    whole reason the port exists: the designer who needs to know their material subtotal is ₹90
 *    short is the one standing in the workshop where the yarn was bought. `GET
 *    /design-workshops/{id}/cost-integrity` is used only when this device has never downloaded stage
 *    17 — and the panel says which of the two it is showing, because the server's answer is computed
 *    from what has been SAVED and the local one from what is on screen.
 *
 * 2. **IT WRITES NOTHING BACK INTO STAGE 17.** Not on mount, not on a verdict, not ever. There is
 *    deliberately no "use the computed figure" affordance: the endpoint's own docstring holds this
 *    invariant ("a subtotal may legitimately differ from its lines, and silently replacing a
 *    considered figure with a computed one would be a worse bug"), the Kotlin card holds it, and the
 *    report must go on printing the designer's typed subtotal. They were in the room when the figure
 *    was decided; the arithmetic was not.
 *
 * 3. **AN UN-ITEMISED SHEET IS NOT A WRONG SHEET.** NOT_ITEMISED, NOT_DECLARED, INCOMPLETE and
 *    NOT_COMPUTABLE are statements about what could not be checked, not accusations, and they are
 *    drawn neutral. Only MISMATCH and BELOW_COST are allowed to interrupt — a panel that shouts at
 *    correct data is one designers learn to dismiss, taking the true findings with it.
 *
 * 4. **AN UNSYNCED SHEET IS NOT A DELETED SHEET.** `costSheetRef` holds the sheet's server id, so a
 *    sheet created in a courtyard cannot be offered to its own lines at all and those lines land in
 *    the orphan bucket — whose caution offers "the sheet they named may have been deleted" as the
 *    explanation. On a screen where the sheet is three rows up waiting for a tower, that sentence is
 *    a false alarm about a morning's costing. The count decides which cautions this screen is
 *    entitled to show; the port's sentences are never edited. Same rule, same count, as the handset.
 */

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

/** The stage this panel appears on. Exported so the stage form has one name for the condition. */
export const COSTING_STAGE = "COSTING_MARKET_LINKAGE";

/**
 * Where a cost sheet's `productRef` points, so a finding can be headed by a product name rather than
 * a raw cuid. A finding a designer cannot trace back to a row is one they cannot act on.
 */
const FINAL_PRODUCTS_STAGE = "FINAL_PROTOTYPE_DOCUMENTATION";

/**
 * A stable empty list.
 *
 * `collections[key] ?? []` hands React a NEW array every render for a collection with no rows yet,
 * which as a `useMemo` dependency re-runs the analysis forever. Every stage-17 workshop starts with
 * all three collections empty, so this is the ordinary case rather than an edge one — the same trap
 * `MarketFindingsPanel` names.
 */
const NO_ROWS: DwRow[] = [];

export function CostFindingsPanel({
  workshopId,
  collections
}: {
  workshopId: string;
  /** Stage 17's rows exactly as the form holds them, so a subtotal typed a second ago is checked. */
  collections: Record<string, DwRow[]>;
}) {
  const sheets = useMemo(() => collections.costSheet ?? NO_ROWS, [collections]);
  const materialLines = useMemo(() => collections.costMaterialLine ?? NO_ROWS, [collections]);
  const labourLines = useMemo(() => collections.costLabourLine ?? NO_ROWS, [collections]);

  /**
   * `productRef` resolved to a product NAME, from stage 16's own rows on this device.
   *
   * One IndexedDB read, no network, and it deliberately does not re-run when a figure is typed —
   * the final products cannot change from this page, and re-reading the draft on every keystroke
   * would put a transaction behind each character on a field laptop. Getting it wrong costs nothing
   * but a readable name (`Cost sheet 3` is the same answer `report_builder._row_label` gives), so it
   * is never allowed to fail the panel.
   */
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
        // A draft that cannot be read costs a readable sheet name and nothing else.
        if (!cancelled) setLabels({});
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [workshopId]);

  /*
    "THIS STAGE HOLDS NOTHING" AND "THIS DEVICE HAS NEVER READ THIS STAGE" ARE DIFFERENT FACTS, and
    only the second one may reach the endpoint. A workshop whose stage 17 genuinely has no rows must
    say so locally; a browser that arrived on the stage without downloading it must not report a
    fully costed workshop as having no sheets. The page hands us the rows it holds, so an empty set is
    the signal — and for a workshop that exists only on this laptop there is no id to ask about
    anyway.
  */
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
        /*
          No connection, or no entitlement. SILENT, and deliberately unlike `MarketFindingsPanel`,
          which says so out loud: that panel sits on stage 9 and reports on stage 8, so a designer
          has no other way to learn the evidence was not read. This one reports on the stage the
          designer is standing IN, and the stage page above it already draws its own banner for a
          stage this device has never downloaded. A second sentence saying the same thing, in a
          panel about arithmetic, would read as a third failure.
        */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [needsServer, workshopId]);

  /*
    The analysis itself. Pure arithmetic over a few dozen rows, so it is a memo rather than state — it
    re-runs on the keystroke that changes a subtotal and never needs invalidating by hand.
  */
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

  // Nothing on the stage and nothing the repository could add: no panel. An empty findings card on a
  // stage a designer has not started is noise on every workshop's first visit to stage 17.
  if (!findings || (!findings.sheets.length && !findings.orphans.length)) return null;

  return (
    <section className="panel mb-5 grid gap-4 p-4" aria-labelledby="cost-findings-heading">
      <h2 id="cost-findings-heading" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
        <Calculator className="h-4 w-4 text-purple-700" aria-hidden />
        Cost sheets against their own lines
      </h2>

      {/*
        RULE 4. Said BEFORE the cautions it suppresses, because the designer's question when a line
        is in no subtotal is "where did my sheet go", and this is the answer.
      */}
      {unsynced ? (
        <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          {unsynced} of {sheets.length} cost sheet{sheets.length === 1 ? "" : "s"} {unsynced === 1 ? "has" : "have"} not
          reached the repository yet, so lines entered against {unsynced === 1 ? "it" : "them"} cannot name their sheet
          and are in no subtotal below. Nothing is lost and nothing needs re-entering — the sheets are on this screen and
          the check completes itself once the workshop has been sent.
        </p>
      ) : null}

      {/*
        WARNINGS ALWAYS, CAUTIONS ONLY WHEN THEY CAN BE TRUE. A warning is a sheet contradicting its
        own lines, which is a fact about figures on this screen and is unaffected by what has synced.
        Every caution here is an ORPHAN caution and every orphan caution offers a deleted sheet as the
        explanation — the wrong explanation, and an alarming one, the moment a sheet on this very
        screen is simply waiting for a tower.
      */}
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

      {/* Where the analysis came from, and therefore how current it is — the same distinction the
          market panel draws, for the same reason: "the repository worked this out from what has been
          saved" and "this laptop worked this out from what is on screen" are different claims. */}
      <p className="text-xs leading-5 text-ink-500">
        {source === "local"
          ? `Computed on this device from ${findings.sheetCount} cost sheet(s), ${materialLines.length} material ` +
            `line(s) and ${labourLines.length} labour line(s) on this page. Nothing here is written into this stage ` +
            "— a subtotal you typed stays exactly as you typed it."
          : "Computed by the repository, because stage 17 has not been downloaded to this device. It describes what " +
            "has been SAVED, so anything typed above and not yet sent is not counted. Nothing here is written back."}
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

/**
 * The verdict, as a word and a tint — in that order of importance.
 *
 * RULE 3. Only MISMATCH and BELOW_COST are drawn as findings; AGREES and COMPUTED are drawn as
 * confirmations; everything else is NEUTRAL, because it describes what could not be checked rather
 * than what is wrong, and an error colour beside "there are no material lines to check this against"
 * tells a designer their un-itemised sheet was refused by a machine.
 *
 * Every state carries a WORD as well as a tint, so the reading survives greyscale, colour-blindness
 * and the glare of a courtyard at noon. A verdict this build has never met prints its own token
 * rather than being swallowed — the server may be ahead of it, and an unnamed finding is still a
 * finding.
 */
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
