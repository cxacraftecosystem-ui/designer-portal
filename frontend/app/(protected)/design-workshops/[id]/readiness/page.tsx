"use client";

/**
 * One screen answering "what still stops this workshop being submitted?"
 *
 * THE PROBLEM IT REPLACES. Completeness has always been computed — per stage, on both ends, and
 * correctly — but it was only ever SHOWN per stage. A designer chasing a submission on the last
 * afternoon of a fortnight had to open all 22 stages to discover that four Basic fields, in three of
 * them, were what stood in the way. Twenty-two page loads to find four boxes, on a laptop that is
 * usually offline, is the reason people submit reports with "Not recorded." printed through them.
 *
 * IT COMPUTES NOTHING. Every number and every entry comes from `lib/submissionReadiness`, which is
 * itself an assembly of `localStageCompleteness` — the same scorer the stage form's own progress bar
 * and the server's `stage_completeness` use. Read that file's header before changing anything here:
 * the one rule this feature has is that it must never become a second opinion about whether a field
 * is filled in.
 *
 * IT WORKS WITH NO NETWORK, which is not a nicety. The list is built from the local draft in
 * IndexedDB, exactly as `MarketFindingsPanel` builds its findings, so the question can be asked in
 * the courtyard where the answer changes what happens next. The server read that follows only
 * refreshes the draft; nothing on this page waits for it.
 *
 * THE RANKING IS THE FEATURE. Unfilled Basic fields come first because they are the only things that
 * 422 a submit. The report's own checks come second because they change the delivered file without
 * refusing it. Standard and Advanced gaps come last, as counts, behind a disclosure, and are never
 * drawn in the same list — a screen that mixes two hundred suggestions into four obstacles teaches a
 * designer to skim the whole thing.
 */

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { AlertTriangle, ArrowRight, CheckCircle2, FileWarning, ListChecks } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { getDesignWorkshop, type DwRegistry } from "@/lib/designWorkshops";
import {
  adoptServerDetail,
  ensureDraft,
  isLocalWorkshopId,
  loadDraft,
  loadRegistry,
  type DwDraft
} from "@/lib/designWorkshopStore";
import { isUnreachable } from "@/lib/offline";
import {
  itemFieldName,
  readinessSummary,
  workshopReadiness,
  type ReadinessItem,
  type WorkshopReadiness
} from "@/lib/submissionReadiness";

/** The outstanding fields of one stage, under one heading a designer can recognise. */
function StageGroup({ stageNumber, stageTitle, items }: { stageNumber: number; stageTitle: string; items: ReadinessItem[] }) {
  return (
    <li className="border-b border-line-200 last:border-b-0">
      <div className="flex items-center gap-3 px-4 pb-1 pt-3">
        <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-field-200 text-xs font-semibold text-ink-700">
          {stageNumber}
        </span>
        <h3 className="min-w-0 flex-1 truncate text-sm font-medium text-ink-900">{stageTitle}</h3>
        <span className="shrink-0 text-xs text-ink-500">
          {items.length} required field{items.length === 1 ? "" : "s"}
        </span>
      </div>
      <ul className="grid">
        {items.map((item) => (
          <li key={`${item.stageKey}:${item.label}`}>
            {/* THE WHOLE ROW IS THE LINK. An item a designer cannot act on in one click has failed,
                and a row where only a small "Open" at the end is clickable is one a thumb misses on
                the phone this list is most often read on. */}
            <Link
              href={item.href}
              className="flex items-center gap-3 py-2 pl-14 pr-4 transition hover:bg-surface-50"
            >
              <span className="min-w-0 flex-1">
                <span className="block text-sm text-ink-900">{itemFieldName(item)}</span>
                <span className="mt-0.5 block text-xs text-ink-500">
                  {item.address ? item.address.entityTitle : item.label}
                  {item.address && item.address.occurrences > 1
                    ? // The denominator is what makes this sizeable work rather than one forgotten box.
                      ` · missing in ${item.address.occurrences} of ${item.address.rowCount} rows`
                    : null}
                  {/* Honest about a link that will land on the stage rather than on the box. Silence
                      here would look like the same promise the addressed rows make and quietly not
                      keep it. */}
                  {item.address ? null : " · opens the stage"}
                </span>
              </span>
              <ArrowRight className="h-4 w-4 shrink-0 text-ink-300" aria-hidden />
            </Link>
          </li>
        ))}
      </ul>
    </li>
  );
}

export default function DesignWorkshopReadinessPage({ params }: { params: Promise<{ id: string }> }) {
  // Next 16 hands route params over as a promise; `use` unwraps it in a client component.
  const { id } = use(params);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [draft, setDraft] = useState<DwDraft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);

  /*
    The same load the stage index performs, and deliberately the same shape: local copy first so the
    list renders with no connection, then a server read that only refreshes it. Copied rather than
    shared because the two pages want different things from the result and a hook that returned
    "everything either might need" would fetch more than either does.
  */
  useEffect(() => {
    let cancelled = false;
    (async () => {
      let loadedRegistry: DwRegistry | null = null;
      try {
        const loaded = await loadRegistry();
        if (cancelled) return;
        loadedRegistry = loaded.registry;
        setRegistry(loaded.registry);
      } catch (err) {
        if (cancelled) return;
        // Without the registry there is no list of required fields at all, so this one is fatal —
        // but say what it is. Rendering "nothing is outstanding" because the declaration could not be
        // read is the single worst thing this page could do.
        setError(
          err instanceof Error
            ? `The list of fields could not be loaded and this browser has no saved copy of it: ${err.message}`
            : "The list of fields could not be loaded and this browser has no saved copy of it."
        );
        return;
      }

      const local = isLocalWorkshopId(id) ? await loadDraft(id) : await ensureDraft(id);
      if (cancelled) return;
      if (local) setDraft(local);

      if (isLocalWorkshopId(id) && local && !local.remoteId) return;

      try {
        const detail = await getDesignWorkshop(local?.remoteId ?? id);
        if (cancelled) return;
        const merged = await adoptServerDetail(detail, loadedRegistry);
        if (cancelled) return;
        setDraft(merged ?? local);
        setError(null);
      } catch (err) {
        if (cancelled) return;
        // `isUnreachable`, not `isTransient`: a 5xx is the repository reporting a fault, and telling
        // a designer to go and look at their signal for it sends them to fix the wrong thing.
        if (isUnreachable(err)) {
          setOffline(true);
          if (!local) setError("There is no connection and this browser has no copy of this workshop.");
          return;
        }
        setError(err instanceof Error ? err.message : "Unable to load this design workshop");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  const readiness: WorkshopReadiness | null = useMemo(
    () => (registry && draft ? workshopReadiness(registry, draft, id) : null),
    [registry, draft, id]
  );

  /** The blocking items regrouped under their stage, in the order they were produced (stage order). */
  const groups = useMemo(() => {
    if (!readiness) return [];
    const out: { stageKey: string; stageNumber: number; stageTitle: string; items: ReadinessItem[] }[] = [];
    for (const item of readiness.blocking) {
      const last = out[out.length - 1];
      if (last && last.stageKey === item.stageKey) last.items.push(item);
      else out.push({ stageKey: item.stageKey, stageNumber: item.stageNumber, stageTitle: item.stageTitle, items: [item] });
    }
    return out;
  }, [readiness]);

  const percent = readiness && readiness.requiredTotal > 0
    ? Math.round((100 * readiness.requiredFilled) / readiness.requiredTotal)
    : 100;

  return (
    <>
      <PageHeader
        title="Ready to submit?"
        description="Every required field still outstanding, and what the report would warn about, computed on this device."
        icon={<ListChecks className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All 22 stages
          </Link>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {offline ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          There is no connection, so this is worked out from the copy saved in this browser. That is the same
          arithmetic the repository does — nothing on this page needs a server to be right.
        </div>
      ) : null}

      {readiness === null ? (
        <section className="panel p-4 text-sm text-ink-700">
          {error ? "Nothing could be worked out." : "Working out what is left…"}
        </section>
      ) : (
        <>
          <section className="panel mb-5 grid gap-4 p-4">
            <div>
              <div className="flex items-center justify-between gap-3 text-sm">
                {/* THE SENTENCE, NOT THE PERCENTAGE, IS THE HEADLINE. 94% reads as "nearly there"
                    whether what is left is one date or a stage nobody has opened. */}
                <span className="font-medium text-ink-900">{readinessSummary(readiness)}</span>
                <span className="shrink-0 text-ink-muted">{percent}%</span>
              </div>
              <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-field-200">
                <div
                  className={
                    readiness.isSubmittable
                      ? "h-full rounded-full bg-success-600"
                      : "h-full rounded-full bg-purple-700"
                  }
                  style={{ width: `${Math.max(0, Math.min(100, percent))}%` }}
                />
              </div>
            </div>
            <p className="text-xs leading-5 text-ink-500">
              Only Basic-tier fields are counted here, because those are the ones a submit refuses without. A stage
              that asks for no required fields reads as complete rather than as 0%.
            </p>
          </section>

          {readiness.isSubmittable ? (
            /* THE COMPLETE CASE IS A STATEMENT, NEVER AN EMPTY LIST. A list with nothing in it and no
               sentence beside it is indistinguishable from a list that failed to load, and this
               screen exists precisely so a designer does not have to go and check by hand. */
            <section className="panel mb-5 grid gap-2 p-4">
              <p className="flex items-center gap-2 text-sm font-medium text-success-600">
                <CheckCircle2 className="h-4 w-4" aria-hidden />
                Every required field across all {readiness.stagesTotal} stages is filled in.
              </p>
              <p className="text-sm leading-6 text-ink-700">
                All {readiness.requiredTotal} of them. Nothing on this workshop will be refused for a missing Basic
                field{readiness.checks.length ? ", though the report has something to say below." : "."}
              </p>
            </section>
          ) : (
            <section className="panel mb-5 overflow-hidden">
              <div className="border-b border-line-200 px-4 py-3">
                <h2 className="text-sm font-semibold text-ink-900">
                  {readiness.blocking.length} required field{readiness.blocking.length === 1 ? "" : "s"} outstanding
                </h2>
                <p className="mt-0.5 text-xs leading-5 text-ink-500">
                  A submit is refused while any of these is empty. Each one opens the stage with the box highlighted.
                </p>
              </div>
              <ul>
                {groups.map((group) => (
                  <StageGroup
                    key={group.stageKey}
                    stageNumber={group.stageNumber}
                    stageTitle={group.stageTitle}
                    items={group.items}
                  />
                ))}
              </ul>
            </section>
          )}

          {readiness.checks.length ? (
            <section className="panel mb-5 overflow-hidden">
              <div className="border-b border-line-200 px-4 py-3">
                <h2 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
                  <FileWarning className="h-4 w-4 text-amber-800" aria-hidden />
                  What the report would warn about
                </h2>
                <p className="mt-0.5 text-xs leading-5 text-ink-500">
                  These do not refuse a submit. They change the file that gets delivered.
                </p>
              </div>
              <ul>
                {readiness.checks.map((check) => (
                  <li key={check.id} className="border-b border-line-200 last:border-b-0">
                    <Link href={check.href} className="flex items-start gap-3 px-4 py-3 transition hover:bg-surface-50">
                      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-medium text-ink-900">{check.title}</span>
                        <span className="mt-0.5 block text-sm leading-6 text-ink-700">{check.detail}</span>
                      </span>
                      <ArrowRight className="mt-0.5 h-4 w-4 shrink-0 text-ink-300" aria-hidden />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {readiness.advisory.length ? (
            /* SUBORDINATE BY CONSTRUCTION, not merely by being lower down: closed by default, no
               status colour, counts rather than named fields. Standard and Advanced answers are what
               make a report good; they are not what makes it acceptable, and a designer with an hour
               left must be able to tell the two apart at a glance. */
            <details className="panel px-4 py-3">
              <summary className="cursor-pointer text-sm font-medium text-ink-700">
                Optional detail not yet filled in ({readiness.advisory.length} stage
                {readiness.advisory.length === 1 ? "" : "s"})
              </summary>
              <p className="mt-2 text-xs leading-5 text-ink-500">
                Standard- and Advanced-tier fields. None of these blocks a submission or produces a report warning —
                they are the depth a report gains when there is time for it.
              </p>
              <ul className="mt-3 grid gap-1">
                {readiness.advisory.map((gap) => (
                  <li key={gap.stageKey}>
                    <Link
                      href={gap.href}
                      className="flex items-center justify-between gap-3 rounded-md px-2 py-1.5 text-sm transition hover:bg-surface-50"
                    >
                      <span className="min-w-0 flex-1 truncate text-ink-700">
                        {gap.stageNumber}. {gap.stageTitle}
                      </span>
                      <span className="shrink-0 text-xs text-ink-500">
                        {gap.optionalFilled} of {gap.optionalTotal}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            </details>
          ) : null}
        </>
      )}
    </>
  );
}
