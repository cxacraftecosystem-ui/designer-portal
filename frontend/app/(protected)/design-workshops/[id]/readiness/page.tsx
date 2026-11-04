"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { AlertTriangle, ArrowRight, CheckCircle2, FileWarning, ListChecks } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { getDesignWorkshop, type DwRegistry } from "@/lib/designWorkshops";
import {
  adoptServerDetail,
  ensureDraft,
  isLocalWorkshopId,
  loadCustomDefinition,
  loadDraft,
  loadRegistry,
  type CustomDefinitionSource,
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

export const STAGE_CHECK_IS =
  "“Save and check required fields” saves the stage and lists any empty required fields.";

export const WORKSHOP_STATUS_IS =
  "Empty fields never block “Mark complete” or “Hand in for inspection”.";

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
            <Link
              href={item.href}
              className="flex items-center gap-3 py-2 pl-14 pr-4 transition hover:bg-surface-50"
            >
              <span className="min-w-0 flex-1">
                <span className="block text-sm text-ink-900">{itemFieldName(item)}</span>
                <span className="mt-0.5 block text-xs text-ink-500">
                  {item.address ? item.address.entityTitle : item.label}
                  {item.address && item.address.occurrences > 1
                    ?
                      ` · missing in ${item.address.occurrences} of ${item.address.rowCount} entries`
                    : null}
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
  const { id } = use(params);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [draft, setDraft] = useState<DwDraft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);
  const [definitionSource, setDefinitionSource] = useState<CustomDefinitionSource | null>(null);
  const uncountedQuestions = definitionSource === "unknown";

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
        setError(
          err instanceof Error
            ? `Couldn't load the form: ${err.message}`
            : "Couldn't load the form. Check your connection."
        );
        return;
      }

      const local = isLocalWorkshopId(id) ? await loadDraft(id) : await ensureDraft(id);
      if (cancelled) return;
      if (local) setDraft(local);

      if (isLocalWorkshopId(id) && local && !local.remoteId) return;

      let settled: DwDraft | null = local;

      try {
        const detail = await getDesignWorkshop(local?.remoteId ?? id);
        if (cancelled) return;
        const merged = await adoptServerDetail(detail, loadedRegistry);
        if (cancelled) return;
        settled = merged ?? settled;
        const target = merged ?? local;
        if (target) {
          void loadCustomDefinition(target).then(async (held) => {
            if (cancelled) return;
            setDefinitionSource(held.source);
            if (!held.definition) return;
            const refreshed = await loadDraft(target.localId);
            if (!cancelled && refreshed) setDraft(refreshed);
          });
        }
        setDraft(target);
        setError(null);
      } catch (err) {
        if (cancelled) return;
        if (isUnreachable(err)) {
          setOffline(true);
          if (!local) setError("You're offline and this workshop isn't on this device.");
          return;
        }
        setError(err instanceof Error ? err.message : "Unable to load this design workshop");
      } finally {
        if (!cancelled) {
          setDefinitionSource((current) => current ?? (settled?.customDefinition ? "cache" : "unknown"));
        }
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
        description="Empty required fields and report warnings."
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
          Offline — using the copy on this device.
          {uncountedQuestions
            ? " This workshop's own questions aren't counted."
            : null}
        </div>
      ) : null}

      {uncountedQuestions ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          Couldn&rsquo;t load this workshop&apos;s own questions, so they aren&rsquo;t counted.
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
                <span className="font-medium text-ink-900">{readinessSummary(readiness)}</span>
                <span className="shrink-0 text-ink-muted">{percent}%</span>
              </div>
              <ProgressBar
                percent={percent}
                tone={readiness.isSubmittable ? "done" : "brand"}
                className="mt-1.5 h-2"
              />
            </div>
            <p className="text-xs leading-5 text-ink-500">
              Counts required fields only.
            </p>
          </section>

          {readiness.isSubmittable ? (
            <section className="panel mb-5 grid gap-2 p-4">
              <p className="flex items-center gap-2 text-sm font-medium text-success-600 dark:text-emerald-400">
                <CheckCircle2 className="h-4 w-4" aria-hidden />
                {uncountedQuestions
                  ? `All required fields of the standard form are filled in.`
                  : `All required fields are filled in.`}
              </p>
              <p className="text-sm leading-6 text-ink-700">
                {uncountedQuestions ? (
                  <>
                    All {readiness.requiredTotal}. Own questions not included{readiness.checks.length ? "; see warnings below." : "."}
                  </>
                ) : (
                  <>
                    All {readiness.requiredTotal}{readiness.checks.length ? " — see warnings below." : "."}
                  </>
                )}
              </p>
            </section>
          ) : (
            <section className="panel mb-5 overflow-hidden">
              <div className="border-b border-line-200 px-4 py-3">
                <h2 className="text-sm font-semibold text-ink-900">
                  {readiness.blocking.length} required field{readiness.blocking.length === 1 ? "" : "s"} outstanding
                </h2>
                <p className="mt-0.5 text-xs leading-5 text-ink-500">
                  {STAGE_CHECK_IS}
                </p>
                <p className="mt-1 text-xs leading-5 text-ink-500">{WORKSHOP_STATUS_IS}</p>
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
                  <FileWarning className="h-4 w-4 text-amber-800 dark:text-amber-100" aria-hidden />
                  What the report would warn about
                </h2>
                <p className="mt-0.5 text-xs leading-5 text-ink-500">
                  These don&rsquo;t block anything, but change the report.
                </p>
              </div>
              <ul>
                {readiness.checks.map((check) => (
                  <li key={check.id} className="border-b border-line-200 last:border-b-0">
                    <Link href={check.href} className="flex items-start gap-3 px-4 py-3 transition hover:bg-surface-50">
                      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-800 dark:text-amber-100" aria-hidden />
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
            <details className="panel px-4 py-3">
              <summary className="cursor-pointer text-sm font-medium text-ink-700">
                Optional detail not yet filled in ({readiness.advisory.length} stage
                {readiness.advisory.length === 1 ? "" : "s"})
              </summary>
              <p className="mt-2 text-xs leading-5 text-ink-500">
                Optional fields that add depth to the report.
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
