import { getDesignWorkshopStage, type DwRegistry, type DwRow, type DwStage } from "@/lib/designWorkshops";
import { adoptServerStage, ensureDraft, loadDraft, loadRegistry } from "@/lib/designWorkshopStore";

import { stageKeyForEntity } from "./reviewRanking";

export type StageRows = {
  stageKey: string | null;
  spec: DwStage | null;
  rows: DwRow[];
  draftId: string | null;
  reconciled: boolean;
};

export async function readRegistry(): Promise<DwRegistry | null> {
  try {
    return (await loadRegistry()).registry;
  } catch {
    return null;
  }
}

export async function readStageRows(
  workshopId: string,
  registry: DwRegistry | null,
  entityKey: string,
  options: {
    fromServer: boolean;
    onLocal?: (rows: DwRow[], draftId: string | null) => void;
  }
): Promise<StageRows> {
  const stageKey = registry ? stageKeyForEntity(registry, entityKey) : null;
  const spec = registry && stageKey ? (registry.stages.find((stage) => stage.key === stageKey) ?? null) : null;
  if (!stageKey || !spec) return { stageKey, spec, rows: [], draftId: null, reconciled: false };

  let draftId: string | null = null;
  let rows: DwRow[] = [];
  try {
    const draft = options.fromServer ? await ensureDraft(workshopId) : await loadDraft(workshopId);
    draftId = draft?.localId ?? null;
    rows = draft?.stages[stageKey]?.collections[entityKey] ?? [];
  } catch {
    draftId = null;
  }
  options.onLocal?.(rows, draftId);

  if (!options.fromServer || !draftId) return { stageKey, spec, rows, draftId, reconciled: false };

  try {
    const data = await getDesignWorkshopStage(workshopId, stageKey);
    const merged = await adoptServerStage(draftId, spec, data);
    const fresh = merged?.stages[stageKey]?.collections[entityKey];
    if (fresh) return { stageKey, spec, rows: fresh, draftId, reconciled: true };
    return { stageKey, spec, rows, draftId, reconciled: true };
  } catch {
    return { stageKey, spec, rows, draftId, reconciled: false };
  }
}

export function rowLabel(row: DwRow, index: number): string {
  for (const key of ["name", "sketchNo", "prototypeCode"]) {
    const value = row[key];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return `Untitled ${index + 1}`;
}

export function rowKeyOf(row: DwRow): string | null {
  return row._clientKey ?? row._entryId ?? null;
}
