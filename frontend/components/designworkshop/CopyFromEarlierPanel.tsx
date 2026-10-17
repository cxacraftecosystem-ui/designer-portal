"use client";

/**
 * "Start from an earlier workshop" on stages 2, 4, 5 and 7 (owner decision C9, 2026-10-17).
 *
 * Two blocks, never mixed:
 *
 * - **Your earlier workshops** — the designer's own text, copied into EMPTY boxes only and marked
 *   "Copied". Copied text does not count toward completeness until the designer edits it or presses
 *   "Keep as is".
 * - **Other designers in this cluster** — read only. Their text is shown to read, never put in a box:
 *   the registry's rule is one author per box.
 *
 * The page owns the boxes; this panel only hands it values (`onCopy`) and asks the server to keep a
 * copy as the designer's own (`onConfirm`).
 */

import { useEffect, useMemo, useState } from "react";
import { ChevronDown, Copy } from "lucide-react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import { ApiError } from "@/lib/api";
import {
  fetchStageCopySources,
  type DwCopySource,
  type DwCopySources,
  type DwValue
} from "@/lib/designWorkshops";

/** A value as one readable line, for the preview. Rich text is flattened to its words. */
export function previewOf(value: DwValue | undefined, limit = 220): string {
  let text = "";
  if (typeof value === "string") text = value;
  else if (Array.isArray(value)) text = value.join(", ");
  else if (value && typeof value === "object") {
    const words: string[] = [];
    const walk = (node: unknown) => {
      if (typeof node === "string") return;
      if (Array.isArray(node)) node.forEach(walk);
      else if (node && typeof node === "object") {
        const record = node as Record<string, unknown>;
        if (typeof record.text === "string") words.push(record.text);
        Object.values(record).forEach(walk);
      }
    };
    walk(value);
    text = words.join(" ");
  }
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length > limit ? `${clean.slice(0, limit - 1)}…` : clean;
}

function SourceCard({
  source,
  labels,
  action
}: {
  source: DwCopySource;
  labels: Record<string, string>;
  action?: React.ReactNode;
}) {
  const meta = [source.date, source.designerName].filter(Boolean).join(" · ");
  return (
    <li className="rounded-md border border-line-200 bg-card p-3">
      <p className="text-sm font-medium text-ink-900">{source.title}</p>
      {meta ? <p className="text-xs text-ink-500">{meta}</p> : null}
      <dl className="mt-2 grid gap-1.5">
        {Object.entries(source.values).map(([key, value]) => (
          <div key={key}>
            <dt className="field-label">{labels[key] ?? key}</dt>
            <dd className="text-sm leading-6 text-ink-700">{previewOf(value)}</dd>
          </div>
        ))}
      </dl>
      {action}
    </li>
  );
}

export function CopyFromEarlierPanel({
  workshopId,
  stageKey,
  disabled,
  onCopy,
  pendingKeys,
  onConfirm,
  confirming
}: {
  /** The SERVER id of this workshop. */
  workshopId: string;
  stageKey: string;
  disabled?: boolean;
  /** Fill the empty boxes; answers how many were filled. */
  onCopy: (sourceWorkshopId: string, values: Record<string, DwValue>) => number;
  /** Boxes holding copied text not yet edited or kept, with their labels. */
  pendingKeys: { key: string; label: string }[];
  onConfirm: (keys: string[]) => void;
  confirming?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [sources, setSources] = useState<DwCopySources | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!open || sources) return;
    let cancelled = false;
    fetchStageCopySources(workshopId, stageKey)
      .then((answer) => {
        if (!cancelled) setSources(answer);
      })
      .catch((error) => {
        if (!cancelled) {
          setProblem(
            error instanceof ApiError && error.message ? error.message : "Couldn't load earlier workshops."
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, [open, sources, workshopId, stageKey]);

  const labels = useMemo(
    () => Object.fromEntries((sources?.fields ?? []).map((field) => [field.key, field.label])),
    [sources]
  );

  return (
    <section className="panel p-4">
      {pendingKeys.length ? (
        <div className="mb-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2">
          <p className="text-sm text-ink-700">
            Copied, not counted until edited or kept: {pendingKeys.map((item) => item.label).join(", ")}.
          </p>
          <ActionGroup align="start" className="mt-2">
            <button
              type="button"
              className="field-button-secondary"
              disabled={disabled || confirming}
              onClick={() => onConfirm(pendingKeys.map((item) => item.key))}
            >
              {confirming ? "Keeping…" : "Keep as is"}
            </button>
          </ActionGroup>
        </div>
      ) : null}
      <button
        type="button"
        className="flex w-full items-center gap-3 rounded-md text-left"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="grid h-9 w-9 place-items-center rounded-lg bg-field-200 text-field-600" aria-hidden>
          <Copy className="h-4 w-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block font-display text-base font-bold tracking-tight text-ink-900">
            Start from an earlier workshop
          </span>
          <span className="block text-xs text-ink-500">Copy your own text into empty boxes.</span>
        </span>
        <ChevronDown className={`h-4 w-4 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>

      {open ? (
        <div className="mt-4 grid gap-4">
          {problem ? <p className="text-sm text-error-600">{problem}</p> : null}
          {notice ? <p className="text-sm text-ink-700" role="status">{notice}</p> : null}
          {sources === null && !problem ? <p className="text-sm text-ink-500">Loading…</p> : null}
          {sources ? (
            <>
              <div>
                <h3 className="font-display text-sm font-bold tracking-tight text-ink-900">Your earlier workshops</h3>
                {sources.own.length === 0 ? (
                  <p className="mt-1 text-sm text-ink-500">Nothing of yours to copy for this stage yet.</p>
                ) : (
                  <ul className="mt-2 grid gap-2">
                    {sources.own.map((source) => (
                      <SourceCard
                        key={source.workshopId}
                        source={source}
                        labels={labels}
                        action={
                          <ActionGroup align="start" className="mt-2">
                            <button
                              type="button"
                              className="field-button"
                              disabled={disabled}
                              onClick={() => {
                                const filled = onCopy(source.workshopId, source.values);
                                setNotice(
                                  filled
                                    ? `${filled === 1 ? "1 box" : `${filled} boxes`} filled from “${source.title}”.`
                                    : "Every box already has an answer. Nothing was replaced."
                                );
                              }}
                            >
                              Copy into empty boxes
                            </button>
                          </ActionGroup>
                        }
                      />
                    ))}
                  </ul>
                )}
              </div>
              {sources.reference.length ? (
                <div>
                  <h3 className="font-display text-sm font-bold tracking-tight text-ink-900">
                    Other designers in this cluster
                  </h3>
                  <p className="mt-1 text-xs text-ink-500">Read only. Write your own in the boxes below.</p>
                  <ul className="mt-2 grid gap-2">
                    {sources.reference.map((source) => (
                      <SourceCard key={source.workshopId} source={source} labels={labels} />
                    ))}
                  </ul>
                </div>
              ) : null}
            </>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
