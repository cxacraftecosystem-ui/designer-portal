import type { DwDraft } from "@/lib/designWorkshopStore";

export function neverReconciled(draft: DwDraft): boolean {
  if (draft.lastSyncedAt !== null && draft.lastSyncedAt !== undefined) return false;
  return !Object.values(draft.stages ?? {}).some(
    (stage) => stage?.serverLoadedAt !== null && stage?.serverLoadedAt !== undefined
  );
}
