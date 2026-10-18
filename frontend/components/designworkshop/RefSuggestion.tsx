"use client";

/**
 * "Use from the sketch" beside an in-workshop picker (owner, C10, 2026-10-17).
 *
 * The registry's `refSuggest` names what may cross: `fill` keys are copied into this row's BLANK
 * boxes on a tap; `hint` keys (the sketch's intended size) are only shown, because a drawing's
 * intention is not the object's measurement. The maker crosses by reference — the same roster row's
 * id. The chosen record is read from this device's draft first and the server second, so it works
 * offline for anything this browser holds.
 */

import { useEffect, useState } from "react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import { listStageReferences, type DwEntity, type DwEntryData, type DwField, type DwValue } from "@/lib/designWorkshops";
import { isLocalWorkshopId, loadDraft } from "@/lib/designWorkshopStore";

type Source = Record<string, unknown> | null;

const blank = (value: unknown) =>
  value === null || value === undefined || value === "" || (Array.isArray(value) && value.length === 0);

async function readSource(workshopId: string, model: string, id: string): Promise<Source> {
  try {
    const draft = await loadDraft(workshopId);
    for (const stage of Object.values(draft?.stages ?? {})) {
      for (const rows of Object.values(stage.collections ?? {})) {
        const found = (rows as Record<string, unknown>[]).find((row) => row._entryId === id || row._clientKey === id);
        if (found) return found;
      }
    }
  } catch {
    // Fall through to the server.
  }
  if (isLocalWorkshopId(workshopId)) return null;
  try {
    const payload = await listStageReferences(workshopId, { model, recordId: id, limit: 1 });
    return payload.options[0]?.data ?? null;
  } catch {
    return null;
  }
}

function describe(value: unknown): string {
  return Array.isArray(value) ? value.join(", ") : String(value);
}

export function sourceNoun(model: string): string {
  return model === "DwSketch" ? "sketch" : model === "DwPrototype" ? "prototype" : "record";
}

export function RefSuggestion({
  entity,
  field,
  row,
  workshopId,
  onPatch,
  disabled
}: {
  entity: DwEntity;
  field: DwField;
  row: DwEntryData;
  workshopId: string;
  onPatch: (values: Record<string, DwValue>) => void;
  disabled?: boolean;
}) {
  const id = typeof row[field.key] === "string" ? (row[field.key] as string) : "";
  const [source, setSource] = useState<{ id: string; data: Source }>({ id: "", data: null });
  useEffect(() => {
    if (!id || !field.refModel) return;
    let cancelled = false;
    void readSource(workshopId, field.refModel, id).then((data) => {
      if (!cancelled) setSource({ id, data });
    });
    return () => {
      cancelled = true;
    };
  }, [id, field.refModel, workshopId]);

  const suggest = field.refSuggest;
  if (!suggest || !id || source.id !== id || !source.data) return null;
  const data = source.data;
  const offered: Record<string, DwValue> = {};
  for (const [from, to] of Object.entries(suggest.fill)) {
    if (!blank(data[from]) && blank(row[to])) offered[to] = data[from] as DwValue;
  }
  const hints = Object.entries(suggest.hint)
    .filter(([from]) => !blank(data[from]))
    .map(([from]) => String(data[from]));
  if (!Object.keys(offered).length && !hints.length) return null;
  const noun = sourceNoun(field.refModel ?? "");
  const labelOf = (key: string) => entity.fields.find((f) => f.key === key)?.label ?? key;
  const parts = Object.entries(offered).map(([key, value]) =>
    key === "artisanRef" ? "the same maker" : `${labelOf(key).toLowerCase()} ${describe(value)}`
  );

  return (
    <div className="mt-2 flex flex-col gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 sm:flex-row sm:items-center">
      <div className="min-w-0 flex-1 text-xs leading-5 text-ink-700">
        {parts.length ? <p>From the {noun}: {parts.join("; ")}.</p> : null}
        {hints.length ? (
          <p className="text-ink-500">
            Intended size {hints.join(" × ")} cm.
          </p>
        ) : null}
      </div>
      {parts.length ? (
        <ActionGroup inline align="end" className="max-sm:w-full">
          <button type="button" className="field-button-secondary" disabled={disabled} onClick={() => onPatch(offered)}>
            Use from the {noun}
          </button>
        </ActionGroup>
      ) : null}
    </div>
  );
}
