import { apiFetch, buildQuery } from "@/lib/api";

export const DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY = "DESIGN_PROTOTYPE_DEVELOPMENT";

export type WorkshopTypeOption = {
  id: string;
  key: string;
  label: string;
  sortOrder: number;
  isActive: boolean;
  routesToDesignWorkshop: boolean;
  createdAt: string | null;
  updatedAt: string | null;
};

export async function listWorkshopTypes(options?: { includeInactive?: boolean }) {
  const query = buildQuery({ includeInactive: options?.includeInactive ? "true" : undefined });
  return apiFetch<WorkshopTypeOption[]>(`/workshop-types${query}`);
}

export function sortWorkshopTypes(types: WorkshopTypeOption[]): WorkshopTypeOption[] {
  return [...types].sort((a, b) => a.sortOrder - b.sortOrder || a.key.localeCompare(b.key));
}

export function designWorkshopTypeOf(types: WorkshopTypeOption[]): WorkshopTypeOption | null {
  return sortWorkshopTypes(types).find((type) => type.routesToDesignWorkshop) ?? null;
}

export function defaultWorkshopType(
  types: WorkshopTypeOption[],
  options?: { preferDesignWorkshops?: boolean }
): WorkshopTypeOption | null {
  const ordered = sortWorkshopTypes(types.filter((type) => type.isActive));
  if (options?.preferDesignWorkshops) {
    const design = ordered.find((type) => type.routesToDesignWorkshop);
    if (design) return design;
  }
  return ordered[0] ?? null;
}

export function workshopTypeLabel(types: WorkshopTypeOption[], key: string | null | undefined) {
  if (!key) return null;
  return types.find((type) => type.key === key)?.label ?? key;
}

export async function createWorkshopType(body: {
  key: string;
  label: string;
  sortOrder?: number;
  isActive?: boolean;
  routesToDesignWorkshop?: boolean;
}) {
  return apiFetch<WorkshopTypeOption>("/workshop-types", { method: "POST", body: JSON.stringify(body) });
}

export async function updateWorkshopType(
  id: string,
  body: { label?: string; sortOrder?: number; isActive?: boolean; routesToDesignWorkshop?: boolean }
) {
  return apiFetch<WorkshopTypeOption>(`/workshop-types/${id}`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

export async function reorderWorkshopTypes(ids: string[]) {
  return apiFetch<WorkshopTypeOption[]>("/workshop-types/reorder", {
    method: "PATCH",
    body: JSON.stringify({ ids })
  });
}

export async function deleteWorkshopType(id: string) {
  await apiFetch<void>(`/workshop-types/${id}`, { method: "DELETE" });
}
