/**
 * THIS PORTAL'S SIDE OF THE SHARED QUESTIONNAIRE SCREENS (designer-portal).
 *
 * `lib/questionnaire/**` and `components/questionnaire/**` are byte-identical in designer-portal and
 * Field Repository (`shared/questionnaire.sha256`). Everything that differs between the two portals —
 * which workshops a session is filed under, how a workshop is listed and named in a title, what the
 * forms list looks like, where the offline lists are kept — is answered here, under this one name,
 * and each portal keeps its own copy of this file. A shared screen imports nothing else portal-own
 * (`shared/questionnaire-web-imports.json`).
 *
 * designer-portal: a session is filed under a DESIGN workshop (`designWorkshopId`), the default is
 * the design workshop the designer was most recently given (`GET /design-workshops/default-for-me`,
 * B30), and the title's workshop half is the workshop's cluster (else venue), district, state and
 * craft — the same fields `app/questionnaire/titles.py` reads for a design workshop.
 */

import { apiFetch, buildQuery } from "@/lib/api";
import type { PageResult } from "@/lib/types";
import type { TitleWorkshop } from "@/lib/naming";

export const PORTAL = "designer-portal" as const;
/** Where the shared screens keep their per-device lists (localStorage / IndexedDB names). */
export const STORAGE_PREFIX = "design_workshop_qv2";
/** The bundle file this portal writes (`shared/questionnaire-bundle.json`); both are read. */
export const BUNDLE_EXTENSION = ".dpwq";
export const BUNDLE_PREFIX = "DesignWorkshop";
/** How the start sheet names the workshop field. */
export const WORKSHOP_LABEL = "Design workshop";
/** The option for a session filed under no workshop. */
export const NO_WORKSHOP_LABEL = "No workshop";
/** The query parameter a session, a coverage grid and an artisan list are scoped with. */
export const WORKSHOP_PARAM = "designWorkshopId" as const;

/** A workshop as the shared screens read it. */
export type PortalWorkshop = {
  id: string;
  title: string;
  /** The day it ran (yyyy-mm-dd), for the default rule and the label. */
  startDate: string | null;
  createdAt: string | null;
  /** The design workshop's kind (its type default applies, owner amendment 2). */
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

/** The design workshops this account works on (the server scopes the list). */
export async function listWorkshops(): Promise<PortalWorkshop[]> {
  const page = await apiFetch<PageResult<DwRow>>(`/design-workshops${buildQuery({ page: 1, pageSize: 100 })}`);
  return (page.items ?? []).map(fromRow);
}

/** B30: the design workshop this designer was most recently given, else the latest one they can record in. */
export async function defaultWorkshopId(workshops: PortalWorkshop[]): Promise<string | null> {
  try {
    const mine = await apiFetch<{ workshopId: string | null }>("/design-workshops/default-for-me");
    if (mine.workshopId && workshops.some((w) => w.id === mine.workshopId)) return mine.workshopId;
  } catch {
    // No signal: the list's own rule below.
  }
  return mostRecentWorkshop(workshops)?.id ?? null;
}

/** The most recent workshop that may still be recorded in, by the day it ran, then by when it was entered. */
export function mostRecentWorkshop(workshops: PortalWorkshop[]): PortalWorkshop | null {
  const today = new Date().toISOString().slice(0, 10);
  const open = workshops.filter((w) => w.canRecord && (!w.startDate || w.startDate.slice(0, 10) <= today));
  open.sort((a, b) => (b.startDate ?? "").localeCompare(a.startDate ?? "") || (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
  return open[0] ?? null;
}

/** The title's workshop half, as the server builds it for a design workshop. */
export function titleWorkshop(w: PortalWorkshop | null | undefined): TitleWorkshop | null {
  if (!w) return null;
  return { locality: w.locality, district: w.district, state: w.state, craft: w.craft };
}

/** `{designWorkshopId}` for a request body or query, or nothing when no workshop is chosen. */
export function workshopScope(id: string | null | undefined): { workshopId: null; designWorkshopId: string | null } {
  return { workshopId: null, designWorkshopId: id || null };
}

/** The artisan list's filter for one workshop. */
export function artisanFilter(id: string | null | undefined): Record<string, string | undefined> {
  return { designWorkshopId: id || undefined };
}

/** Where a workshop's own page is. */
export function workshopHref(id: string): string {
  return `/design-workshops/${encodeURIComponent(id)}`;
}

/** A form as the start sheet and the coverage page list it. */
export type PortalForm = {
  id: string;
  title: string;
  kind: string | null;
  isDefault: boolean;
  isShared: boolean;
  ownerId: string | null;
};

type FormRow = { id: string; title: string; kind?: string | null; isDefault?: boolean; isShared?: boolean; ownerId?: string | null };

/** Every live form this account can see (its own, the shared ones, its workshops'). */
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

/** The forms library page and one form's page. */
export const FORMS_HREF = "/questionnaires";
export function formHref(id: string): string {
  return `/questionnaires/${encodeURIComponent(id)}`;
}

/** A colleague to add as a session researcher (B6): the directory every signed-in user may read. */
export async function searchPeople(search: string): Promise<{ id: string; name: string }[]> {
  const rows = await apiFetch<{ id: string; name: string }[]>(`/users/directory${buildQuery({ search: search || undefined })}`);
  return rows.map((r) => ({ id: r.id, name: r.name }));
}

/**
 * The workshop coverage page's scope (B12): a design workshop's artisans (`Artisan.designWorkshopId`),
 * asked by id, since a design workshop is not a coverage column of its own.
 */
export async function coverageScope(workshopId: string): Promise<{ workshopId?: string; artisanIds?: string[] }> {
  const page = await apiFetch<PageResult<{ id: string }>>(`/artisans${buildQuery({ page: 1, pageSize: 100, designWorkshopId: workshopId })}`);
  return { artisanIds: (page.items ?? []).map((a) => a.id) };
}

/**
 * The answer box's editor (decision C8). designer-portal answers with its own rich-text editor and
 * sends the canonical plain text with the document beside it; see `AnswerTextEditor`.
 */
export { AnswerTextEditor, answerEditorValue, answerParts } from "@/components/questionnaires/AnswerTextEditor";
