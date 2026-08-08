"use client";

/**
 * WHAT THE SURVEY SAYS — stage 9's declared analysis, held up against the stage-8 rows it is
 * supposed to be an analysis OF.
 *
 * THE PROBLEM IT SOLVES. Stage 8 captures a real market survey: respondent groups, price
 * expectations, products discussed, competitor products and their prices. Stage 9 then asks the
 * designer to write down demand, price bands, a competitor analysis and a SWOT. Nothing connected
 * the two. A designer typed "₹400–800, high demand" into stage 9 having collected twenty-three
 * price expectations in stage 8, and no part of the system ever compared the two numbers. The
 * report printed the assertion, the evidence sat one stage away, and a reviewer at the ministry had
 * no way to tell a considered band from a guess.
 *
 * FOUR RULES, EACH LOAD-BEARING.
 *
 * 1. **IT COMPUTES ON THIS DEVICE.** `lib/marketAnalysis.ts` is a port of
 *    `backend/app/services/market_analysis.py` — the same pure arithmetic, proved to the rupee
 *    against it — so the findings come from the rows already in IndexedDB and appear with no
 *    network at all. That is the entire reason the port exists: the designer who most needs to know
 *    that their band is ₹300 under what buyers said is the one standing in the village where they
 *    asked. `GET /design-workshops/{id}/market-analysis` is used only when this device has never
 *    downloaded stage 8, and the panel says which of the two it is showing, because the server's
 *    answer is computed from what has been SAVED and the local one from what is on screen.
 *
 * 2. **CAUTIONS AND UNSUPPORTED CLAIMS COME FIRST, BEFORE ANY FIGURE.** A caution that has to be
 *    scrolled to is a caution that will not be read, and the whole value of this rests on a designer
 *    seeing "these figures come from one respondent group" BEFORE they see the figures. The wire
 *    payload names them at the top of the object for the same reason; this renders them in that
 *    order rather than beside the sections they qualify.
 *
 * 3. **IT NEVER WRITES TO STAGE 9.** Not on mount, not on a verdict, not ever. The designer's
 *    declared bands, SWOT and demand level stay exactly as typed — they were in the room and the
 *    arithmetic was not. There is deliberately no "apply this band" affordance at all: the moment a
 *    finding can rewrite a field, a designer reading a page of them is one mis-tap from replacing
 *    their own judgement with a machine's, and this analysis is explicitly a hint for a human to
 *    check rather than a claim of proof. If one is ever added it must be a button somebody presses,
 *    never a side effect of rendering.
 *
 * 4. **UNVERIFIABLE IS NOT AN ERROR.** It is a statement about SAMPLE SIZE — "seven price
 *    expectations cannot tell you your band is wrong" — and styling it as a fault would tell a
 *    designer their considered band was refused by a machine counting seven numbers, which is how a
 *    tool stops being believed. Only LOW, HIGH and NARROW are drawn as findings to act on, and
 *    every one of them carries a WORD as well as a tint, so the reading survives greyscale,
 *    colour-blindness and the glare of a courtyard at noon.
 */

import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, BarChart3, Info } from "lucide-react";

import { getDesignWorkshopMarketAnalysis, type DwRow } from "@/lib/designWorkshops";
import { isLocalWorkshopId, loadDraft, stageHoldsSomething } from "@/lib/designWorkshopStore";
import {
  analysePayload,
  formatRupeeAmount,
  type BandPayload,
  type MarketFindingsPayload,
  type MarketRow
} from "@/lib/marketAnalysis";

/** The stage whose rows the findings are computed FROM. Stage 9 holds the claims, 8 holds the evidence. */
const SURVEY_STAGE = "MARKET_SURVEY_CAPTURE";

/** The stage this panel appears on. Exported so the stage form has one name for the condition. */
export const ANALYSIS_STAGE = "MARKET_ANALYSIS_DIRECTION";

/**
 * A stable empty list.
 *
 * `collections[key] ?? []` hands React a NEW array on every render for a collection that has no rows
 * yet, which as a `useMemo` dependency is a fresh identity every pass and re-runs the analysis
 * forever. Every stage-9 workshop starts with all four collections empty, so this is the ordinary
 * case rather than an edge one.
 */
const NO_ROWS: DwRow[] = [];

type Source = "local" | "server";

/**
 * Where the rows for stage 8 came from, and whether there are any.
 *
 * `null` means "not resolved yet" — distinct from "resolved, and this device holds nothing", which
 * is what sends the panel to the endpoint.
 */
type SurveyRows = { responses: MarketRow[]; competitors: MarketRow[] } | null;

export function MarketFindingsPanel({
  workshopId,
  collections
}: {
  workshopId: string;
  /** Stage 9's rows exactly as the form holds them, so a band edited a second ago is judged. */
  collections: Record<string, DwRow[]>;
}) {
  const [survey, setSurvey] = useState<SurveyRows>(null);
  const [resolved, setResolved] = useState(false);
  const [remote, setRemote] = useState<MarketFindingsPayload | null>(null);
  const [remoteFailed, setRemoteFailed] = useState(false);

  const bands = useMemo(() => collections.priceBand ?? NO_ROWS, [collections]);
  const swot = useMemo(() => collections.swotPoint ?? NO_ROWS, [collections]);

  /*
    READ STAGE 8 OFF THIS DEVICE. One IndexedDB read, no network, and it does not re-run when a band
    is typed — the survey rows cannot change from this page, and re-reading the draft on every
    keystroke would put a transaction behind each character on a field laptop.
  */
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const draft = await loadDraft(workshopId);
        if (cancelled) return;
        const stage = draft?.stages[SURVEY_STAGE];
        // "DOWNLOADED AND EMPTY" AND "NEVER DOWNLOADED" ARE DIFFERENT FACTS. A workshop whose
        // stage 8 genuinely has no rows must compute locally and say "no prices were recorded";
        // a device that has simply never read stage 8 must not, because it would report a survey
        // of forty people as no evidence at all and mark every SWOT point unsupported.
        const held = Boolean(stage) && (stage?.serverLoadedAt !== null || stageHoldsSomething(stage));
        setSurvey(
          held
            ? {
                responses: (stage?.collections.surveyResponse ?? []) as MarketRow[],
                competitors: (stage?.collections.competitorProduct ?? []) as MarketRow[]
              }
            : null
        );
      } catch {
        // A draft that cannot be read is the same situation as one that holds nothing: fall through
        // to the endpoint rather than showing findings computed from an absence.
        if (!cancelled) setSurvey(null);
      } finally {
        if (!cancelled) setResolved(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [workshopId]);

  /*
    THE FALLBACK, AND ONLY THE FALLBACK. Reached when stage 8 is not on this device — never before
    the local read has answered, and never at all for a workshop the server has no id for.
  */
  const needsServer = resolved && survey === null && !isLocalWorkshopId(workshopId);
  useEffect(() => {
    if (!needsServer) return;
    let cancelled = false;
    (async () => {
      try {
        const payload = await getDesignWorkshopMarketAnalysis(workshopId);
        if (!cancelled) setRemote(payload);
      } catch {
        // No connection, or no entitlement. Either way the honest answer is the sentence below, not
        // a set of findings computed from rows this browser does not have.
        if (!cancelled) setRemoteFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [needsServer, workshopId]);

  /*
    The analysis itself. Pure arithmetic over a few dozen rows, so it is a memo rather than state —
    it re-runs on the keystroke that changes a band and never needs to be invalidated by hand.
  */
  const local = useMemo(
    () =>
      survey
        ? analysePayload({
            responses: survey.responses,
            competitors: survey.competitors,
            bands: bands as MarketRow[],
            swot: swot as MarketRow[]
          })
        : null,
    [survey, bands, swot]
  );

  const findings = local ?? remote;
  const source: Source = local ? "local" : "server";

  if (!resolved) return null;

  if (!findings) {
    return (
      <section className="panel grid gap-2 p-4" aria-labelledby="market-findings-heading">
        <Heading />
        <p className="text-sm leading-6 text-ink-700">
          {remoteFailed
            ? "This device has not downloaded stage 8, and the repository could not be reached to ask it. " +
              "Open stage 8 once with a connection and these findings are computed here from then on, with or " +
              "without one."
            : "Reading the market survey…"}
        </p>
      </section>
    );
  }

  const unsupported = findings.unsupportedSwot;
  const respondent = findings.respondentPrices;
  const competitor = findings.competitorPrices;

  return (
    <section className="panel grid gap-4 p-4" aria-labelledby="market-findings-heading">
      <Heading />

      {/* FIRST, ALWAYS — above even the sentence saying where the analysis came from, because that
          sentence carries the observation COUNT and the rule is that no figure precedes a caution.
          See rule 2 in the file header: a caution below the figures has already failed to do its
          job, and the first thing a designer must know is what the numbers cannot bear. */}
      {findings.cautions.length ? (
        <div className="grid gap-1.5 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-amber-800">
          <p className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide">
            <AlertTriangle className="h-3.5 w-3.5" aria-hidden />
            Read these first
          </p>
          <ul className="grid gap-1 text-sm leading-6">
            {findings.cautions.map((caution) => (
              <li key={caution}>{caution}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {unsupported.length ? (
        <div className="grid gap-1.5 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-amber-800">
          <p className="text-xs font-semibold uppercase tracking-wide">
            {unsupported.length} SWOT point{unsupported.length === 1 ? "" : "s"} the survey does not back
          </p>
          <ul className="grid gap-1 text-sm leading-6">
            {unsupported.map((point) => (
              <li key={`${point.kind}:${point.point}`}>
                <span className="font-medium">{point.kind || "SWOT"}</span> — {point.point}
              </li>
            ))}
          </ul>
          {/* The honest framing. Shared vocabulary is a retrieval result, not a judgement: it says
              nobody in the survey used these words, which is a reason to cite the evidence in the
              row's own Evidence box, not proof that the claim is false. */}
          <p className="text-xs leading-5">
            No survey response shares wording with {unsupported.length === 1 ? "it" : "them"}, and no evidence was
            cited in the row. That is a search result, not a verdict — if the claim is sound, naming its source in
            the Evidence column is what a reviewer will look for.
          </p>
        </div>
      ) : null}

      {/* Where the analysis came from, and therefore how current it is. Placed after the cautions
          and before the first finding: "the repository worked this out from what has been saved"
          and "this laptop worked this out from what is on screen" are different claims, and a
          designer reading a verdict on a band they typed two minutes ago has to know which. */}
      <p className="text-xs leading-5 text-ink-500">
        {source === "local"
          ? `Computed on this device from ${findings.observations} price observation(s) in stage 8 and the rows ` +
            "on this page. Nothing here is written to stage 9 — these are findings beside your answers, not " +
            "instead of them."
          : `Computed by the repository from ${findings.observations} price observation(s), because stage 8 has ` +
            "not been downloaded to this device. It describes what has been SAVED, so anything typed above and " +
            "not yet sent is not counted."}
      </p>

      {findings.bands.length ? (
        <div className="grid gap-2">
          <h3 className="text-sm font-medium text-ink-900">Your price bands against the prices recorded</h3>
          <ul className="grid gap-2">
            {findings.bands.map((band, index) => (
              <li
                key={`${band.category}-${index}`}
                className="grid gap-1 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium text-ink-900">{band.category || "No category named"}</span>
                  <VerdictChip verdict={band.verdict} />
                </div>
                <p className="text-sm leading-6 text-ink-700">{band.message}</p>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {findings.competitors.length ? (
        <div className="grid gap-2">
          <h3 className="text-sm font-medium text-ink-900">Competitor prices against what buyers said</h3>
          <ul className="grid gap-1.5">
            {findings.competitors.map((row, index) => (
              <li key={`${row.name}-${index}`} className="text-sm leading-6 text-ink-700">
                <span className="font-medium text-ink-900">{row.name}</span>
                {row.seller ? <span className="text-ink-500"> · {row.seller}</span> : null} — {row.note}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {respondent || competitor ? (
        <div className="grid gap-2 sm:grid-cols-2">
          <DistributionCard title="What buyers said they would pay" dist={respondent} />
          <DistributionCard title="What competitors charge" dist={competitor} />
        </div>
      ) : null}

      {findings.clusters.length ? (
        <div className="grid gap-1">
          <h3 className="text-sm font-medium text-ink-900">Products discussed together</h3>
          <ul className="flex flex-wrap gap-1.5">
            {findings.clusters.map((cluster) => (
              <li
                key={cluster.members.join("|")}
                className="rounded-full bg-field-100 px-2.5 py-1 text-xs text-ink-700"
              >
                {cluster.members.join(" + ")} · {cluster.mentions} mention{cluster.mentions === 1 ? "" : "s"}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}

function Heading() {
  return (
    <h2 id="market-findings-heading" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
      <BarChart3 className="h-4 w-4 text-purple-700" aria-hidden />
      What the survey says
    </h2>
  );
}

/**
 * The verdict, as a word and a tint — in that order of importance.
 *
 * UNVERIFIABLE and NO_EVIDENCE are drawn NEUTRAL, not as faults: they describe how much evidence
 * exists, and a designer whose band is greeted by an error colour because seven people were asked
 * will read it as a criticism of the band. LOW, HIGH and NARROW are the three that a designer can do
 * something about, so those are the three that are allowed to interrupt.
 */
function VerdictChip({ verdict }: { verdict: BandPayload["verdict"] }) {
  const actionable = verdict === "LOW" || verdict === "HIGH" || verdict === "NARROW";
  const tone = verdict === "SOUND"
    ? "bg-success-100 text-success-600"
    : actionable
      ? "bg-amber-100 text-amber-800"
      : "bg-field-100 text-ink-500";
  const label =
    verdict === "SOUND" ? "Matches the evidence"
      : verdict === "LOW" ? "Below the evidence"
        : verdict === "HIGH" ? "Above the evidence"
          : verdict === "NARROW" ? "Misses most of the evidence"
            : verdict === "UNVERIFIABLE" ? "Too few observations to judge"
              : verdict === "NO_EVIDENCE" ? "Nothing recorded to check against"
                // A verdict this build has not met — the server is ahead of it. Print the token
                // rather than swallowing it: an unnamed finding is still a finding.
                : verdict;
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${tone}`}>
      {actionable ? <AlertTriangle className="h-3.5 w-3.5" aria-hidden /> : <Info className="h-3.5 w-3.5" aria-hidden />}
      {label}
    </span>
  );
}

/**
 * One sample's shape.
 *
 * `quantilesReported` is honoured rather than ignored: below five observations the quartiles are
 * filled with the median, and printing them as quartiles would dress two numbers up as a spread.
 */
function DistributionCard({
  title,
  dist
}: {
  title: string;
  dist: MarketFindingsPayload["respondentPrices"];
}) {
  if (!dist) return null;
  // `formatRupeeAmount` rather than `toLocaleString`: the verdict sentences beside this card are
  // written by the analysis itself and group as 100,000, while an `en-IN` locale groups the same
  // number as 1,00,000. Two spellings of one figure in one panel read as two figures.
  return (
    <div className="grid gap-0.5 rounded-md border border-line-200 bg-surface-50 px-3 py-2">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-500">{title}</p>
      <p className="text-sm text-ink-900">
        ₹{formatRupeeAmount(dist.minimum)} – ₹{formatRupeeAmount(dist.maximum)}
      </p>
      <p className="text-xs leading-5 text-ink-500">
        {dist.count} observation{dist.count === 1 ? "" : "s"} · median ₹{formatRupeeAmount(dist.median)}
        {dist.quantilesReported
          ? ` · middle half ₹${formatRupeeAmount(dist.p25)}–${formatRupeeAmount(dist.p75)}`
          : " · too few to report quartiles"}
      </p>
    </div>
  );
}
