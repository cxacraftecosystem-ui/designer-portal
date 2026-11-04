import { apiFetch, buildQuery } from "@/lib/api";
import type { PageResult } from "@/lib/types";
import type { TitleWorkshop } from "@/lib/naming";

export const PORTAL = "designer-portal" as const;
export const STORAGE_PREFIX = "design_workshop_qv2";
export const BUNDLE_EXTENSION = ".dpwq";
export const BUNDLE_PREFIX = "DesignWorkshop";
export const WORKSHOP_LABEL = "Design workshop";
export const NO_WORKSHOP_LABEL = "No workshop";
export const WORKSHOP_PARAM = "designWorkshopId" as const;

export type PortalWorkshop = {
  id: string;
  title: string;
  startDate: string | null;
  createdAt: string | null;
  kind: string | null;
  locality: string | null;
  district: string | null;
  state: string | null;
  craft: string | null;
  canRecord: boolean;
};

type DwRow = {
  id: string;
  title: string;
  status?: string | null;
  startDate?: string | null;
  createdAt?: string | null;
  workshopKind?: string | null;
  clusterName?: string | null;
  venue?: string | null;
  district?: string | null;
  state?: string | null;
  craftName?: string | null;
};

function fromRow(row: DwRow): PortalWorkshop {
  const locality = (row.clusterName ?? "").trim() || (row.venue ?? "").trim() || null;
  return {
    id: row.id,
    title: row.title,
    startDate: row.startDate ?? null,
    createdAt: row.createdAt ?? null,
    kind: row.workshopKind ?? null,
    locality,
    district: row.district ?? null,
    state: row.state ?? null,
    craft: (row.craftName ?? "").trim() || null,
    canRecord: row.status !== "SUBMITTED" && row.status !== "APPROVED"
  };
}

export async function listWorkshops(): Promise<PortalWorkshop[]> {
  const page = await apiFetch<PageResult<DwRow>>(`/design-workshops${buildQuery({ page: 1, pageSize: 100 })}`);
  return (page.items ?? []).map(fromRow);
}

export async function defaultWorkshopId(workshops: PortalWorkshop[]): Promise<string | null> {
  try {
    const mine = await apiFetch<{ workshopId: string | null }>("/design-workshops/default-for-me");
    if (mine.workshopId && workshops.some((w) => w.id === mine.workshopId)) return mine.workshopId;
  } catch {
  }
  return mostRecentWorkshop(workshops)?.id ?? null;
}

export function mostRecentWorkshop(workshops: PortalWorkshop[]): PortalWorkshop | null {
  const today = new Date().toISOString().slice(0, 10);
  const open = workshops.filter((w) => w.canRecord && (!w.startDate || w.startDate.slice(0, 10) <= today));
  open.sort((a, b) => (b.startDate ?? "").localeCompare(a.startDate ?? "") || (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
  return open[0] ?? null;
}

export function titleWorkshop(w: PortalWorkshop | null | undefined): TitleWorkshop | null {
  if (!w) return null;
  return { locality: w.locality, district: w.district, state: w.state, craft: w.craft };
}

export function workshopScope(id: string | null | undefined): { workshopId: null; designWorkshopId: string | null } {
  return { workshopId: null, designWorkshopId: id || null };
}

export function artisanFilter(id: string | null | undefined): Record<string, string | undefined> {
  return { designWorkshopId: id || undefined };
}

export function workshopHref(id: string): string {
  return `/design-workshops/${encodeURIComponent(id)}`;
}

export type PortalForm = {
  id: string;
  title: string;
  kind: string | null;
  isDefault: boolean;
  isShared: boolean;
  ownerId: string | null;
};

type FormRow = { id: string; title: string; kind?: string | null; isDefault?: boolean; isShared?: boolean; ownerId?: string | null };

export async function listForms(): Promise<PortalForm[]> {
  const page = await apiFetch<PageResult<FormRow> | FormRow[]>(`/questionnaires${buildQuery({ page: 1, pageSize: 100 })}`);
  const rows = Array.isArray(page) ? page : (page.items ?? []);
  return rows.map((r) => ({
    id: r.id,
    title: r.title,
    kind: r.kind ?? null,
    isDefault: Boolean(r.isDefault),
    isShared: Boolean(r.isShared),
    ownerId: r.ownerId ?? null
  }));
}

export const FORMS_HREF = "/questionnaires";
export function formHref(id: string): string {
  return `/questionnaires/${encodeURIComponent(id)}`;
}

export async function searchPeople(search: string): Promise<{ id: string; name: string }[]> {
  const rows = await apiFetch<{ id: string; name: string }[]>(`/users/directory${buildQuery({ search: search || undefined })}`);
  return rows.map((r) => ({ id: r.id, name: r.name }));
}

export async function coverageScope(workshopId: string): Promise<{ workshopId?: string; artisanIds?: string[] }> {
  const page = await apiFetch<PageResult<{ id: string }>>(`/artisans${buildQuery({ page: 1, pageSize: 100, designWorkshopId: workshopId })}`);
  return { artisanIds: (page.items ?? []).map((a) => a.id) };
}

export { AnswerTextEditor, answerEditorValue, answerParts } from "@/components/questionnaires/AnswerTextEditor";

export { FilterMultiSelect, type FilterMultiSelectProps } from "@/components/questionnaires/FilterMultiSelect";
