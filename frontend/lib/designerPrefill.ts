import { apiFetch } from "@/lib/api";
import type { DwEntryData, DwStage, DwValue } from "@/lib/designWorkshops";

export type DesignerPrefill = { designerId: string | null; values: Record<string, DwValue> };

export const DESIGNER_PREFILL_STORAGE_PREFIX = "dw_designer_prefill_v1:";

export function isEmptyStageValue(value: DwValue | undefined): boolean {
  return (
    value === undefined ||
    value === null ||
    (typeof value === "string" && !value.trim()) ||
    (Array.isArray(value) && value.length === 0)
  );
}

export function prefillPatch(
  stage: Pick<DwStage, "entities"> | null | undefined,
  singleton: DwEntryData,
  values: Record<string, DwValue> | null | undefined
): Record<string, DwValue> {
  const patch: Record<string, DwValue> = {};
  if (!stage || !values) return patch;
  const fields = new Set<string>();
  for (const entity of stage.entities) {
    if (entity.cardinality !== "SINGLETON") continue;
    for (const field of entity.fields) if (!field.deprecated) fields.add(field.key);
  }
  for (const [key, value] of Object.entries(values)) {
    if (!fields.has(key) || isEmptyStageValue(value)) continue;
    if (isEmptyStageValue(singleton[key])) patch[key] = value;
  }
  return patch;
}

function readCached(workshopId: string): DesignerPrefill | null {
  try {
    const text = window.localStorage.getItem(DESIGNER_PREFILL_STORAGE_PREFIX + workshopId);
    if (!text) return null;
    const parsed = JSON.parse(text) as Partial<DesignerPrefill>;
    if (!parsed || typeof parsed.values !== "object" || parsed.values === null) return null;
    return { designerId: typeof parsed.designerId === "string" ? parsed.designerId : null, values: parsed.values };
  } catch {
    return null;
  }
}

function writeCached(workshopId: string, prefill: DesignerPrefill) {
  try {
    window.localStorage.setItem(DESIGNER_PREFILL_STORAGE_PREFIX + workshopId, JSON.stringify(prefill));
  } catch {
  }
}

export async function loadDesignerPrefill(workshopId: string): Promise<DesignerPrefill | null> {
  try {
    const answer = await apiFetch<DesignerPrefill>(`/design-workshops/${encodeURIComponent(workshopId)}/designer-prefill`);
    const prefill = { designerId: answer?.designerId ?? null, values: answer?.values ?? {} };
    writeCached(workshopId, prefill);
    return prefill;
  } catch {
    return readCached(workshopId);
  }
}
