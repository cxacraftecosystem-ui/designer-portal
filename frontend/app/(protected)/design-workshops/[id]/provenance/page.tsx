"use client";

import { use, useEffect, useMemo, useState } from "react";
import { GitCompareArrows, Loader2 } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { fieldProvenanceLine } from "@/components/designworkshop/FieldProvenance";
import type { DwRegistry } from "@/lib/designWorkshops";
import { loadRegistry } from "@/lib/designWorkshopStore";
import {
  comparisonText,
  divergedFields,
  divergenceTally,
  fetchWorkshopProvenance,
  type DwProvenanceEntry,
  type DwProvenanceReport
} from "@/lib/designWorkshopProvenance";

/**
 * **THE ADMIN AUTHORSHIP AND DIVERGENCE VIEW FOR ONE WORKSHOP.**
 *
 * ── THE ONE THING THIS PAGE SHOWS THAT NOTHING ELSE CAN ───────────────────────────────────────
 *
 * Every designer on a workshop already sees the per-field stamps: they ride on the ordinary stage
 * read and render under each box. This page adds the half nobody else can see — for every value
 * COPIED from a shared canonical record, what that record says **today**, beside what this workshop
 * stored.
 *
 * That comparison is not derivable anywhere else. Once a value is hydrated onto a stage entry it is
 * an ordinary string: a hydrated village and a typed village are the same bytes, deliberately, so a
 * workshop keeps what the designer saw on the day. Only the `reference` stamp — which names the
 * record and the column — plus a live read of that record can say "this workshop says Barpali and
 * the artisan record now says Bargarh".
 *
 * ── DIVERGENCE IS NOT AN ERROR AND THE COPY HERE NEVER IMPLIES IT IS ──────────────────────────
 *
 * A workshop is a DATED OBSERVATION. An artisan who moved village after the workshop makes every
 * participant row diverge, and every one of those rows is correct. So this page has no warning
 * colours, no "fix" control, and no count of "problems": it has two columns and a neutral word.
 * The failure it exists to prevent is an admin being unable to explain why a submitted report and
 * the live directory disagree — not a designer being told off.
 *
 * ── WHY THE EMPTY STATE IS A FIRST-CLASS RENDERING ────────────────────────────────────────────
 *
 * No divergence is the common case, and an admin opening this page wants that answered in one line
 * rather than after scrolling twenty-two stages. So the tally leads, and a workshop with nothing to
 * compare says so in a sentence instead of showing an empty table that reads like a failed load.
 */

/** One entry's diverged fields, or null when it has none. */
function DivergenceCard({
  entry,
  entityTitle
}: {
  entry: DwProvenanceEntry;
  entityTitle: (stageKey: string, entityKey: string) => string;
}) {
  const diverged = divergedFields(entry);
  if (!diverged.length) return null;

  return (
    <li className="panel p-4">
      <h3 className="font-display text-sm font-bold text-ink-900">
        {entityTitle(entry.stageKey, entry.entityKey)}
        {typeof entry.ordinal === "number" ? ` · row ${entry.ordinal + 1}` : ""}
      </h3>
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
                {/* The stamp under the field name, in the same words the stage form uses. An admin
                    comparing two columns needs to know WHICH record the left one came out of, and
                    that is exactly what the reference stamp says. */}
                <span className="block text-[0.6875rem] leading-4 text-ink-300">
                  {fieldProvenanceLine(entry.fields?.[fieldKey])}
                </span>
              </td>
              <td className="py-1.5 pr-3 text-ink-900">{comparisonText(comparison.stored)}</td>
              <td className="py-1.5 text-ink-900">
                {comparison.canonical === null || comparison.canonical === undefined ? (
                  // NOT AN ERROR, AND THE MOST INTERESTING STATE ON THIS PAGE. A deleted canonical
                  // record is precisely the case reference hydration exists for: the workshop still
                  // holds what the designer saw, and it is now the only copy.
                  <span className="text-ink-500">The record this came from no longer exists</span>
                ) : (
                  comparisonText(comparison.canonical)
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
  // Next 16 hands route params over as a promise; `use` unwraps it in a client component.
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
          // Only so stage and entity keys can be shown as the titles a person recognises. A failed
          // registry load is not a failed page — the keys are still readable — so it is caught
          // separately and never surfaces as an error over the comparison itself.
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

  /** A stage/entity key pair as the words the registry gives them, falling back to the keys. */
  const entityTitle = useMemo(() => {
    return (stageKey: string, entityKey: string) => {
      const stage = registry?.stages?.find((s) => s.key === stageKey);
      const entity = stage?.entities?.find((e) => e.key === entityKey);
      if (!stage) return `${stageKey} · ${entityKey}`;
      return `${stage.title} · ${entity?.title ?? entityKey}`;
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
              A workshop keeps what the designer saw on the day, so a value that differs from the
              shared record today is <strong>not an error</strong> — an artisan who moved village
              after the workshop makes every row that names them differ, and every one of those rows
              is right. This page exists so the difference can be seen and explained, not corrected.
            </p>
            {tally.fields === 0 ? (
              <p className="mt-2 text-sm leading-6 text-ink-500">
                Every value this workshop copied from a shared record still matches that record.
              </p>
            ) : null}
          </section>

          {tally.fields > 0 ? (
            <ul className="grid gap-4">
              {report.entries.map((entry) => (
                <DivergenceCard key={entry.entryId} entry={entry} entityTitle={entityTitle} />
              ))}
            </ul>
          ) : null}
        </div>
      )}
    </>
  );
}
