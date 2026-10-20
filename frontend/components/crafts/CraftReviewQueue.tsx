"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";

import { CraftSelect } from "@/components/forms/CraftSelect";
import { CraftSubCategorySelect } from "@/components/crafts/CraftTaxonomyFields";
import { adoptCraft, mergeCraft } from "@/components/crafts/craftAdmin";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { apiFetch } from "@/lib/api";
import { countNoun } from "@/lib/format";
import { loadFullCraftCatalogue, type CatalogueCraft, type CraftCatalogue } from "@/lib/craftCatalogue";
import { RECORD_UPDATE_HREF } from "@/lib/recordView";

/** One row of `GET /crafts/review` (content admins): what the mapping would do with it. */
export type CraftReviewEntry = {
  kind: "craft" | "freeText";
  id: string;
  /** The Prisma delegate the row lives in: `artisan`, `productdocumentation`, … */
  model: string;
  text: string;
  action: string;
  target: string | null;
  targetId: string | null;
  subCategoryId: string | null;
  score: number | null;
  references: number;
};
export type CraftReviewQueueAnswer = { crafts: CraftReviewEntry[]; records: CraftReviewEntry[] };

export const loadCraftReviewQueue = () => apiFetch<CraftReviewQueueAnswer>("/crafts/review");

/** What a record with typed craft text is called, and where its craft is picked. */
export function reviewRecordTarget(model: string, id: string): { noun: string; href: string } | null {
  switch (model) {
    case "artisan":
      return { noun: "Artisan", href: RECORD_UPDATE_HREF.artisan(id) };
    case "productdocumentation":
      return { noun: "Product", href: RECORD_UPDATE_HREF.product(id) };
    case "tooldocumentation":
      return { noun: "Tool", href: RECORD_UPDATE_HREF.tool(id) };
    case "designworkshop":
      return { noun: "Design workshop", href: `/design-workshops/${encodeURIComponent(id)}/edit` };
    case "annualplanentry":
      return { noun: "Annual plan entry", href: "/annual-plan" };
    default:
      return null;
  }
}

/**
 * "Needs review" (REQUIREMENTS_2026-10-20 §2.5): crafts that predate the master list and did not
 * map. Each is merged into a craft on the list or added to the list in a sub-category. Nothing here
 * deletes a craft: a merge keeps the old row so old links still resolve.
 */
export function CraftReviewQueue({ catalogue, onChanged }: { catalogue: CraftCatalogue; onChanged: () => void }) {
  const [full, setFull] = useState<CraftCatalogue | null>(null);
  const [queue, setQueue] = useState<CraftReviewQueueAnswer | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    let cancelled = false;
    Promise.all([loadFullCraftCatalogue(), loadCraftReviewQueue()])
      .then(([answer, review]) => {
        if (!cancelled) {
          setFull(answer);
          setQueue(review);
          setError(null);
        }
      })
      .catch((caught: unknown) => {
        if (!cancelled) setError(caught instanceof Error ? caught.message : "The review list could not be loaded.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => load(), [load]);

  const legacy = useMemo(() => (full?.crafts ?? []).filter((craft) => craft.status === "LEGACY"), [full]);
  const plans = useMemo(() => new Map((queue?.crafts ?? []).map((row) => [row.id, row])), [queue]);
  const records = queue?.records ?? [];
  const names = useMemo(() => new Map((full?.crafts ?? []).map((craft) => [craft.id, craft.name])), [full]);
  /** The suggestion by the craft's name, not the mapping's slug label. */
  const suggested = (row: CraftReviewEntry | null) => (row?.targetId ? (names.get(row.targetId) ?? row.target) : (row?.target ?? null));

  if (error) {
    return (
      <div className="panel flex flex-wrap items-center justify-between gap-3 p-4 text-sm text-ink-700">
        <span>{error}</span>
        <button type="button" className="field-button-secondary" onClick={load}>
          Try again
        </button>
      </div>
    );
  }
  if (!full || !queue) return <div className="panel p-4 text-sm text-ink-500">Loading…</div>;
  if (!legacy.length && !records.length) return <div className="panel p-4 text-sm text-ink-700">Every craft is on the list.</div>;

  return (
    <div className="grid gap-6">
      {legacy.length ? (
        <section className="grid gap-3" aria-labelledby="craft-review-crafts">
          <h2 id="craft-review-crafts" className="font-display text-sm font-bold tracking-tight text-ink-900">
            Crafts not on the list
          </h2>
          <ul className="grid gap-3">
            {legacy.map((craft) => (
              <ReviewRow
                key={craft.id}
                craft={craft}
                plan={plans.get(craft.id) ?? null}
                suggestion={suggested(plans.get(craft.id) ?? null)}
                catalogue={catalogue}
                onDone={() => {
                  load();
                  onChanged();
                }}
              />
            ))}
          </ul>
        </section>
      ) : null}
      {records.length ? (
        <section className="grid gap-3" aria-labelledby="craft-review-records">
          <h2 id="craft-review-records" className="font-display text-sm font-bold tracking-tight text-ink-900">
            Records with a typed craft
          </h2>
          <ul className="grid gap-3">
            {records.map((record) => {
              const target = reviewRecordTarget(record.model, record.id);
              return (
                <li key={`${record.model}-${record.id}`} className="panel flex flex-wrap items-center justify-between gap-3 p-4">
                  <div className="min-w-0">
                    <p className="font-medium text-ink-900">“{record.text}”</p>
                    <p className="text-xs text-ink-500">
                      {target?.noun ?? "Record"}
                      {suggested(record) ? ` · Suggested: ${suggested(record)}` : ""}
                    </p>
                  </div>
                  {target ? (
                    <ActionGroup inline align="end">
                      <Link href={target.href} className="field-button-secondary">
                        Pick craft
                      </Link>
                    </ActionGroup>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

function ReviewRow({
  craft,
  plan,
  suggestion,
  catalogue,
  onDone
}: {
  craft: CatalogueCraft;
  plan: CraftReviewEntry | null;
  suggestion: string | null;
  catalogue: CraftCatalogue;
  onDone: () => void;
}) {
  const [mode, setMode] = useState<"merge" | "adopt" | null>(null);
  // The mapping's suggestion, if it has one, is where "Merge into…" starts.
  const [intoId, setIntoId] = useState(plan?.targetId ?? "");
  const [subCategoryId, setSubCategoryId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function apply() {
    setBusy(true);
    setError(null);
    try {
      if (mode === "merge") await mergeCraft(craft.id, intoId);
      else if (mode === "adopt") await adoptCraft(craft.id, subCategoryId);
      onDone();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "That did not save. Try again.");
    } finally {
      setBusy(false);
    }
  }

  const references = craft.referenceCount ?? 0;
  const ready = mode === "merge" ? Boolean(intoId) : mode === "adopt" ? Boolean(subCategoryId) : false;

  return (
    <li className="panel grid gap-3 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="font-medium text-ink-900">{craft.name}</p>
          <p className="text-xs text-ink-500">
            Used by {countNoun(references, "record")}
            {suggestion ? ` · Suggested: ${suggestion}` : ""}
          </p>
        </div>
        <ActionGroup align="end">
          <button type="button" className={mode === "merge" ? "field-button" : "field-button-secondary"} onClick={() => setMode("merge")}>
            Merge into…
          </button>
          <button type="button" className={mode === "adopt" ? "field-button" : "field-button-secondary"} onClick={() => setMode("adopt")}>
            Add to list…
          </button>
        </ActionGroup>
      </div>
      {mode === "merge" ? (
        <FieldBlock label="Merge into">
          <CraftSelect ariaLabel="Merge into" value={intoId} onChange={(id) => setIntoId(id)} disabled={busy} />
        </FieldBlock>
      ) : null}
      {mode === "adopt" ? (
        <CraftSubCategorySelect catalogue={catalogue} value={subCategoryId} onChange={setSubCategoryId} disabled={busy} />
      ) : null}
      {error ? (
        <p role="alert" className="text-sm text-error-600">
          {error}
        </p>
      ) : null}
      {mode ? (
        <ActionGroup>
          <button type="button" className="field-button-secondary" disabled={busy} onClick={() => setMode(null)}>
            Cancel
          </button>
          <button type="button" className="field-button" disabled={busy || !ready} onClick={() => void apply()}>
            {busy ? "Saving…" : mode === "merge" ? "Merge" : "Add to list"}
          </button>
        </ActionGroup>
      ) : null}
    </li>
  );
}
