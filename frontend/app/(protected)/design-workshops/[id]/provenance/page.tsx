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
                {/* The stamp under the field name, in the same words the stage form uses. An admin
                    comparing two columns needs to know WHICH record the left one came out of, and
                    that is exactly what the reference stamp says. */}
                <span className="block text-[0.6875rem] leading-4 text-ink-300">
                  {fieldProvenanceLine(entry.fields?.[fieldKey])}
                </span>
              </td>
              <td className="py-1.5 pr-3 text-ink-900">{comparisonText(comparison.stored)}</td>
              <td className="py-1.5 text-ink-900">
                {comparison.recordDeleted ? (
                  // NOT AN ERROR, AND THE MOST INTERESTING STATE ON THIS PAGE. A deleted canonical
                  // record is precisely the case reference hydration exists for: the workshop still
                  // holds what the designer saw, and it is now the only copy.
                  //
                  // KEYED ON THE FLAG, NOT ON `canonical === null`. This page keyed it on the null
                  // and was therefore wrong about a case it had a name for: a record that is
                  // present with a blanked column answers `canonical: null, recordDeleted: false`,
                  // and this cell told an admin that record had been deleted. That one renders as
                  // the em dash — "the record says nothing here" — via `canonicalText`.
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

  /**
   * One entry's heading: the stage and entity in the registry's words, plus the row number.
   *
   * THE ROW CLAUSE IS FOR COLLECTIONS ONLY, AND THE ORDINAL CANNOT TELL YOU WHICH IT IS. This read
   * `typeof entry.ordinal === "number"`, which is true for `0` — and `DwStageEntry.ordinal` is
   * `Int @default(0)`, non-nullable, emitted unconditionally by the route, with the schema's own
   * comment reading "A singleton's is always 0". So EVERY singleton entry was headed "· row 1" of
   * a list that does not exist. The registry knows the difference and is already loaded, so ask it.
   *
   * The clause is one-based because it is shown to a person: `_ordinal` is zero-based everywhere in
   * the protocol, and "row 0" is a sentence no admin counting participants down a table will match
   * against what is in front of them. A stage or entity the registry has never heard of falls back
   * to the key — the registry is fetched separately and may be a release behind the server — and an
   * unknown entity gets no row clause, since nothing here can say whether it repeats.
   */
  const entryTitle = useMemo(() => {
    return (entry: DwProvenanceEntry) => {
      const stage = registry?.stages?.find((s) => s.key === entry.stageKey);
      const entity = stage?.entities?.find((e) => e.key === entry.entityKey);
      const head = stage
        ? `${stage.title} · ${entity?.title ?? entry.entityKey}`
        : `${entry.stageKey} · ${entry.entityKey}`;
      if (entity?.cardinality !== "COLLECTION" || typeof entry.ordinal !== "number") return head;
      return `${head} · row ${entry.ordinal + 1}`;
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
                <DivergenceCard key={entry.entryId} entry={entry} entryTitle={entryTitle} />
              ))}
            </ul>
          ) : null}
        </div>
      )}
    </>
  );
}
