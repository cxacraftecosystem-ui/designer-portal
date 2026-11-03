"use client";

import { countNoun } from "@/lib/format";
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

const SURVEY_STAGE = "MARKET_SURVEY_CAPTURE";

export const ANALYSIS_STAGE = "MARKET_ANALYSIS_DIRECTION";

const NO_ROWS: DwRow[] = [];

type Source = "local" | "server";

type SurveyRows = { responses: MarketRow[]; competitors: MarketRow[] } | null;

export function MarketFindingsPanel({
  workshopId,
  collections
}: {
  workshopId: string;
  collections: Record<string, DwRow[]>;
}) {
  const [survey, setSurvey] = useState<SurveyRows>(null);
  const [resolved, setResolved] = useState(false);
  const [remote, setRemote] = useState<MarketFindingsPayload | null>(null);
  const [remoteFailed, setRemoteFailed] = useState(false);

  const bands = useMemo(() => collections.priceBand ?? NO_ROWS, [collections]);
  const swot = useMemo(() => collections.swotPoint ?? NO_ROWS, [collections]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const draft = await loadDraft(workshopId);
        if (cancelled) return;
        const stage = draft?.stages[SURVEY_STAGE];
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
        if (!cancelled) setSurvey(null);
      } finally {
        if (!cancelled) setResolved(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [workshopId]);

  const needsServer = resolved && survey === null && !isLocalWorkshopId(workshopId);
  useEffect(() => {
    if (!needsServer) return;
    let cancelled = false;
    (async () => {
      try {
        const payload = await getDesignWorkshopMarketAnalysis(workshopId);
        if (!cancelled) setRemote(payload);
      } catch {
        if (!cancelled) setRemoteFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [needsServer, workshopId]);

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
            ? "Open stage 8 once online to see findings here."
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
          <p className="text-xs leading-5">
            No survey response or evidence supports {unsupported.length === 1 ? "it" : "them"}. Add a source in Evidence.
          </p>
        </div>
      ) : null}

      <p className="text-xs leading-5 text-ink-500">
        {source === "local"
          ? `From ${countNoun(findings.observations, "price observation")}. Not saved to stage 9.`
          : `From ${countNoun(findings.observations, "saved price observation")}. Unsaved edits aren't counted.`}
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
      <BarChart3 className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
      What the survey says
    </h2>
  );
}

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
                : verdict;
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${tone}`}>
      {actionable ? <AlertTriangle className="h-3.5 w-3.5" aria-hidden /> : <Info className="h-3.5 w-3.5" aria-hidden />}
      {label}
    </span>
  );
}

function DistributionCard({
  title,
  dist
}: {
  title: string;
  dist: MarketFindingsPayload["respondentPrices"];
}) {
  if (!dist) return null;
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
