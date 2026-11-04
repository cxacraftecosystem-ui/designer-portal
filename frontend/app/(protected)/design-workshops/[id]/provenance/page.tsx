"use client";

import { use, useEffect, useMemo, useState } from "react";
import { GitCompareArrows, Loader2 } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { fieldProvenanceLine } from "@/components/designworkshop/FieldProvenance";
import type { DwRegistry } from "@/lib/designWorkshops";
import { loadRegistry } from "@/lib/designWorkshopStore";
import {
  canonicalText,
  comparisonText,
  divergedFields,
  divergenceTally,
  fetchWorkshopProvenance,
  RECORD_DELETED_TEXT,
  type DwProvenanceEntry,
  type DwProvenanceReport
} from "@/lib/designWorkshopProvenance";

function DivergenceCard({
  entry,
  entryTitle
}: {
  entry: DwProvenanceEntry;
  entryTitle: (entry: DwProvenanceEntry) => string;
}) {
  const diverged = divergedFields(entry);
  if (!diverged.length) return null;

  return (
    <li className="panel p-4">
      <h3 className="font-display text-sm font-bold text-ink-900">{entryTitle(entry)}</h3>
      <table className="mt-3 w-full text-sm">
        <thead>
          <tr className="text-left text-xs uppercase tracking-wide text-ink-500">
            <th className="pb-1 pr-3 font-medium">Field</th>
            <th className="pb-1 pr-3 font-medium">This workshop</th>
            <th className="pb-1 font-medium">The record today</th>
          </tr>
        </thead>
        <tbody>
          {diverged.map(([fieldKey, comparison]) => (
            <tr key={fieldKey} className="border-t border-line-200 align-top">
              <td className="py-1.5 pr-3 text-ink-700">
                {fieldKey}
                <span className="block text-[0.6875rem] leading-4 text-ink-500">
                  {fieldProvenanceLine(entry.fields?.[fieldKey])}
                </span>
              </td>
              <td className="py-1.5 pr-3 text-ink-900">{comparisonText(comparison.stored)}</td>
              <td className="py-1.5 text-ink-900">
                {comparison.recordDeleted ? (
                  <span className="text-ink-500">{RECORD_DELETED_TEXT}</span>
                ) : (
                  canonicalText(comparison)
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </li>
  );
}

export default function DesignWorkshopProvenancePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);

  const [report, setReport] = useState<DwProvenanceReport | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const [fetched, reg] = await Promise.all([
          fetchWorkshopProvenance(id),
          loadRegistry()
            .then((held) => held.registry)
            .catch(() => null)
        ]);
        if (!live) return;
        setReport(fetched);
        setRegistry(reg);
      } catch (err) {
        if (live) setError(err instanceof Error ? err.message : "Could not load the provenance view.");
      }
    })();
    return () => {
      live = false;
    };
  }, [id]);

  const entryTitle = useMemo(() => {
    return (entry: DwProvenanceEntry) => {
      const stage = registry?.stages?.find((s) => s.key === entry.stageKey);
      const entity = stage?.entities?.find((e) => e.key === entry.entityKey);
      const head = stage
        ? `${stage.title} · ${entity?.title ?? entry.entityKey}`
        : `${entry.stageKey} · ${entry.entityKey}`;
      if (entity?.cardinality !== "COLLECTION" || typeof entry.ordinal !== "number") return head;
      return `${head} · entry ${entry.ordinal + 1}`;
    };
  }, [registry]);

  const tally = useMemo(() => (report ? divergenceTally(report) : null), [report]);

  return (
    <>
      <PageHeader title="Authorship & divergence" />
      {error ? (
        <section className="panel p-5 text-sm text-rose-700">{error}</section>
      ) : !report || !tally ? (
        <section className="panel flex items-center gap-2 p-5 text-sm text-ink-500">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> Comparing this workshop against the
          shared records…
        </section>
      ) : (
        <div className="grid gap-4">
          <section className="panel p-5">
            <div className="flex items-center gap-2.5">
              <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
                <GitCompareArrows className="h-4 w-4" aria-hidden />
              </span>
              <h2 className="font-display font-bold text-ink-900">
                {tally.fields === 0
                  ? "Nothing has moved since this workshop was recorded"
                  : `${tally.fields} field${tally.fields === 1 ? "" : "s"} across ${tally.entries} record${
                      tally.entries === 1 ? "" : "s"
                    } now differ`}
              </h2>
            </div>
            <p className="mt-2 text-sm leading-6 text-ink-500">
              The workshop keeps what was true on the day, so a difference is <strong>not an error</strong>.
            </p>
            {tally.fields === 0 ? (
              <p className="mt-2 text-sm leading-6 text-ink-500">
                Everything still matches.
              </p>
            ) : null}
          </section>

          {tally.fields > 0 ? (
            <ul className="grid gap-4">
              {report.entries.map((entry) => (
                <DivergenceCard key={entry.entryId} entry={entry} entryTitle={entryTitle} />
              ))}
            </ul>
          ) : null}
        </div>
      )}
    </>
  );
}
