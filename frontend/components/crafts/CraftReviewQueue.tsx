"use client";

import { useCallback, useEffect, useMemo, useState } from "react";

import { CraftSelect } from "@/components/forms/CraftSelect";
import { CraftSubCategorySelect } from "@/components/crafts/CraftTaxonomyFields";
import { adoptCraft, mergeCraft } from "@/components/crafts/craftAdmin";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { countNoun } from "@/lib/format";
import { loadFullCraftCatalogue, type CatalogueCraft, type CraftCatalogue } from "@/lib/craftCatalogue";

/**
 * "Needs review" (REQUIREMENTS_2026-10-20 §2.5): crafts that predate the master list and did not
 * map. Each is merged into a craft on the list or added to the list in a sub-category. Nothing here
 * deletes a craft: a merge keeps the old row so old links still resolve.
 */
export function CraftReviewQueue({ catalogue, onChanged }: { catalogue: CraftCatalogue; onChanged: () => void }) {
  const [full, setFull] = useState<CraftCatalogue | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    let cancelled = false;
    loadFullCraftCatalogue()
      .then((answer) => {
        if (!cancelled) {
          setFull(answer);
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
  if (!full) return <div className="panel p-4 text-sm text-ink-500">Loading…</div>;
  if (!legacy.length) return <div className="panel p-4 text-sm text-ink-700">Every craft is on the list.</div>;

  return (
    <ul className="grid gap-3">
      {legacy.map((craft) => (
        <ReviewRow
          key={craft.id}
          craft={craft}
          catalogue={catalogue}
          onDone={() => {
            load();
            onChanged();
          }}
        />
      ))}
    </ul>
  );
}

function ReviewRow({ craft, catalogue, onDone }: { craft: CatalogueCraft; catalogue: CraftCatalogue; onDone: () => void }) {
  const [mode, setMode] = useState<"merge" | "adopt" | null>(null);
  const [intoId, setIntoId] = useState("");
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
          <p className="text-xs text-ink-500">Used by {countNoun(references, "record")}</p>
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
