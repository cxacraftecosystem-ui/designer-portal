/**
 * THE SIXTH SCOPE, CLIENT-SIDE: who supervises a design & prototype workshop, and the artisan list.
 *
 * `backend/app/services/design_workshop_oversight.py` is the argument in full and this file is the
 * wire. What follows is the part a caller on this side has to hold in their head, because each of
 * these is a place a reasonable instinct gives the wrong answer.
 *
 * ── 1. TWO DOORS, DISJOINT AUDIENCES, AND NEITHER IS THE OTHER'S SUPERSET ─────────────────────
 *
 * `canAssignWorkshopOversight` is `{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}` and
 * `canReadWorkshopOversight` is `{ASSISTANT_DIRECTOR, REGIONAL_DIRECTOR, MINISTRY_ADMIN}`. An ADMIN
 * may assign and may not be assigned; an ASSISTANT DIRECTOR the reverse; a MINISTRY_ADMIN is in
 * both. **A REGIONAL DIRECTOR IS REFUSED THE ASSIGNMENT SCREEN EVEN THOUGH THEY OUTRANK AN
 * ASSISTANT DIRECTOR** — the supervised do not choose the supervisor.
 *
 * ── 2. WHY THESE PAGES EXIST AT ALL — AND THE REASON CHANGED ON 2026-09-14 ────────────────
 *
 * THE ORIGINAL REASON, KEPT BECAUSE IT EXPLAINS THE SHAPE OF THIS MODULE: an officer was outside
 * `DESIGN_WORKSHOP_ROLES`, `load_workshop_or_404` refused anybody outside that frozenset before it
 * looked at anything, and so `/design-workshops/{id}` and every page beneath it answered a **404**
 * to an Assistant Director or a Regional Director. These pages are the surface that was built
 * instead, and that is why they render the inspection surface's components rather than the
 * workshop's own.
 *
 * **THAT IS NO LONGER WHY.** The three directorate tiers joined `DESIGN_WORKSHOP_ROLES` on the
 * owner's ruling, so `load_workshop_or_404` now admits them by role and the workshop tree is
 * reachable. What keeps these pages worth having is the second half, which did not change: a
 * monitored workshop is one an officer was ASSIGNED, the assignment is what `readOnly: true` below
 * is about, and an officer holding an oversight assignment is not thereby a designer on that
 * workshop. Reaching a workshop still needs the creator arm, an admin, or a viewer grant.
 *
 * AND ONE THING THAT DOES STILL REFUSE THEM, WHICH IS WORTH KNOWING HERE: an officer may not author
 * the workshop their OWN sanction order opened —
 * `services/design_workshops._refuse_if_the_officer_is_authoring_what_they_sanctioned` answers 403
 * on the write and leaves the read alone, so a monitored workshop can still be read end to end.
 *
 * ── 3. `readOnly: true` IS ON THE WIRE ON PURPOSE ─────────────────────────────────────────────
 *
 * Both clients will eventually render the officer's payload through the same screen as the
 * designer's read, and a screen that cannot tell the two apart offers a Save button the API answers
 * 404 to. An ABSENT flag is treated as read-only here, which is the opposite of how `truncated` is
 * treated — see {@link oversightIsReadOnly}.
 *
 * ── 4. WHAT THE OFFICER'S READ DELIBERATELY DOES NOT CARRY ────────────────────────────────────
 *
 * `transcripts` is absent, and it is an absence with a decision behind it: an officer holds no
 * `DataAccessGrant`, no upload of their own and no viewer row, so the media predicate would cost a
 * query to produce an empty list — and would put that route on the media path at all, where the
 * next person widening that predicate would widen this surface without noticing. **Whether an
 * officer should see the photographs and recordings is an owner's decision that has not been
 * made**, so today the answer is no, stated once. A screen over this payload must therefore SAY
 * that media are not part of an oversight read rather than draw an empty gallery — the two look
 * identical and only one of them is true.
 *
 * ── 5. THE PUT IS PER CAPACITY, AND AN OMITTED KEY IS NOT A NULL ──────────────────────────────
 *
 * Omitting a key leaves that capacity exactly as it stands; sending `null` deletes the row. Three
 * requests, three outcomes. A screen that always sends both fields can never mean "leave the other
 * one alone", which is why {@link putWorkshopOversight} takes a partial and why callers must build
 * it from what the reader actually changed.
 *
 * ── 6. WHY THIS FILE IS NOT IN `lib/` ─────────────────────────────────────────────────────────
 *
 * Because every other client module for a design-workshop scope is, and this one should be too:
 * `lib/designWorkshopInspections.ts` is its exact shape one scope over. It is colocated under the
 * route it serves because `frontend/lib/` was owned by another workstream in the wave that wrote
 * it, and a cross-workstream edit there silently destroys the other change. **Moving it to
 * `lib/designWorkshopOversight.ts` is a rename and nothing else** — nothing here imports from the
 * route tree.
 */

import { ApiError, API_BASE, apiFetch, assertApiConfigured, buildQuery, getToken } from "@/lib/api";
import type { DwStageCompleteness, DwStageData, DwSummary } from "@/lib/designWorkshops";
import type { PageResult } from "@/lib/types";

/** How long a search box on either picker may get. Mirrors the routes' `Query(max_length=120)`. */
export const OVERSIGHT_SEARCH_MAX = 120;

/** The two capacities, in the order a report prints them. Mirrors `CAPACITIES` on the server. */
export const CAPACITIES = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"] as const;
export type DwOversightCapacity = (typeof CAPACITIES)[number];

export const CAPACITY_LABELS: Record<DwOversightCapacity, string> = {
  ASSISTANT_DIRECTOR: "Assistant Director",
  REGIONAL_DIRECTOR: "Regional Director"
};

/** One oversight row as the assignment screen reads it. */
export type DwOversightAssignment = {
  capacity: DwOversightCapacity;
  userId: string;
  /**
   * `name`/`email`/`role` travel WITH the row rather than being joined against the directory this
   * screen also holds: an officer whose account has since been suspended is precisely the row an
   * administrator most needs to see and act on, and a join against the eligible list would render
   * it as a bare cuid.
   */
  name: string;
  email: string;
  role: string;
  /** `assignedAt`, never `createdAt` — "since when has THIS PERSON been its AD". */
  assignedAt: string | null;
  assignedById: string | null;
};

export type DwWorkshopOversight = {
  workshopId: string;
  title: string | null;
  status: string;
  designerName: string | null;
  oversight: DwOversightAssignment[];
};

export type DwOfficer = {
  id: string;
  name: string;
  email: string;
  role: string;
  /**
   * Which slots this account may be filed in — **empty for a MINISTRY_ADMIN**, which is the point.
   * The picker greys such a row out with the reason rather than letting the PUT 422 it: a directory
   * that offers a choice the write refuses teaches people to distrust it.
   */
  capacities: DwOversightCapacity[];
};

export type DwOfficerDirectory = { users: DwOfficer[]; truncated: boolean };

/**
 * The officer's designer picker. **FOUR KEYS AND NO ROSTER COLUMNS**, and the absence is the point:
 * `rosterId`, `rosterActive`, `canSignIn`, `firstSeenAt`, `hasProfile` and `institution` are facts
 * about the EMPANELMENT roster, which is the admin's table — and whether a designer has a
 * suspension on file is not an officer's business. The suspended are already absent by the time
 * this renders, because the roster fold is inside the server query's WHERE.
 */
export type DwAssignableDesigner = { id: string; name: string; email: string; role: string };

export type DwAssignableDesignerList = { users: DwAssignableDesigner[]; truncated: boolean };

/** One thing the importer could not do cleanly, in terms an officer can act on. */
export type ImportProblem = {
  sheet: string;
  /**
   * The 1-based worksheet row exactly as Excel's row gutter shows it, so "row 34" means press
   * Ctrl+G and type 34. **`null` means the problem is about the SHEET rather than about a row** —
   * an ignored column, a second sheet that was not used.
   */
  row: number | null;
  severity: "error" | "warning";
  reason: string;
  /**
   * The offending cell, **already masked by the server** for any identity number. Nothing on this
   * side re-masks it: a mask applied in two places is a mask that can be forgotten in one.
   */
  value?: string | null;
};

export type ArtisanImportReport = {
  importId: string;
  sheet: string | null;
  rowsRead: number;
  artisansCreated: number;
  artisansLinked: number;
  rowsRefused: number;
  participantsCreated: number;
  problems: ImportProblem[];
};

export type DwArtisanImport = {
  id: string;
  sourceFilename: string | null;
  sheetName: string | null;
  rowsRead: number;
  artisansCreated: number;
  artisansLinked: number;
  rowsRefused: number;
  participantsCreated: number;
  problems: ImportProblem[];
  uploadedBy: string | null;
  createdAt: string | null;
};

export type DwArtisanImportList = { imports: DwArtisanImport[] };

/** The officer's read of one workshop — the designer's payload minus everything that writes. */
export type DwOversightDetail = DwSummary & {
  /** Keyed by stage key, exactly as the designer's read groups them — same serialiser, not a copy. */
  stages: Record<string, DwStageData>;
  completeness: Record<string, DwStageCompleteness>;
  schemaVersion: string;
  /** A second string, never folded into the one above: the registry's version and the workshop's
   *  own custom-section version move independently. */
  customSchemaVersion?: string;
  /** Who supervises this workshop, so the officer reading it can see the other capacity. */
  oversight?: DwOversightAssignment[];
  /**
   * The server's own word for "this is a read". See {@link oversightIsReadOnly}, which is the only
   * thing that should ever read this key directly.
   */
  readOnly?: boolean;
};

// --- The assignment screen ------------------------------------------------------------------

export function listOfficers(search?: string) {
  return apiFetch<DwOfficerDirectory>(
    `/design-workshop-oversight/officers${buildQuery({ search: search || undefined })}`
  );
}

export function listAssignableDesigners(search?: string) {
  return apiFetch<DwAssignableDesignerList>(
    `/design-workshop-oversight/designers${buildQuery({ search: search || undefined })}`
  );
}

/**
 * The workshops an assigner may name people on — **every one of them**.
 *
 * ⚠ **`GET /design-workshops` IS THE WRONG LIST HERE AND IT FAILS SILENTLY.** That route does not
 * refuse a Ministry Admin; it scopes anybody who is not an admin to the viewer relation, which a
 * Ministry Admin holds on no workshop — so it answers 200 with an EMPTY page, on the first control
 * of the assignment screen, in a repository full of workshops. `DesignWorkshopSelect` is built over
 * that route, which is why this screen does not use it.
 */
export function listAssignableWorkshops(params: { page?: number; pageSize?: number; search?: string }) {
  return apiFetch<PageResult<DwSummary>>(
    `/design-workshop-oversight/workshops${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search || undefined
    })}`
  );
}

export function getWorkshopOversight(workshopId: string) {
  return apiFetch<DwWorkshopOversight>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}`
  );
}

/**
 * Set the two capacities. **A key that is absent from `body` is left exactly as it stands; an
 * explicit `null` unassigns.** Build the object from what the reader changed, never from the whole
 * form — a screen that always sends both can never mean "leave the other one alone".
 */
export function putWorkshopOversight(
  workshopId: string,
  body: { assistantDirectorId?: string | null; regionalDirectorId?: string | null }
) {
  return apiFetch<{ oversight: DwOversightAssignment[] }>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}`,
    { method: "PUT", body: JSON.stringify(body) }
  );
}

export function putWorkshopDesigner(workshopId: string, designerId: string) {
  return apiFetch<{ designerId: string; designerName: string | null; stagesWritten: string[] }>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/designer`,
    { method: "PUT", body: JSON.stringify({ designerId }) }
  );
}

export function listArtisanImports(workshopId: string) {
  return apiFetch<DwArtisanImportList>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisan-imports`
  );
}

// --- The officer's own surface --------------------------------------------------------------

export function listOverseenWorkshops(params: {
  page?: number;
  pageSize?: number;
  search?: string;
}) {
  return apiFetch<PageResult<DwSummary>>(
    `/design-workshop-oversight/assigned${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search || undefined
    })}`
  );
}

export function getOverseenWorkshop(workshopId: string) {
  return apiFetch<DwOversightDetail>(
    `/design-workshop-oversight/assigned/${encodeURIComponent(workshopId)}`
  );
}

/**
 * Is this payload read-only? **An ABSENT flag answers `true`.**
 *
 * That is the opposite of how `truncated` is treated two files over, and deliberately: the two
 * absences mean opposite things. A missing `truncated` is a server that did not cut the list, and
 * reading it as "cut" would print a warning about nothing. A missing `readOnly` is a server that
 * did not say — and the only safe reading of silence on a surface whose entire premise is that
 * nothing here writes is that nothing here writes. Defaulting the other way offers a Save button
 * the API answers 404 to.
 */
export function oversightIsReadOnly(
  detail: { readOnly?: boolean } | null | undefined
): boolean {
  return detail?.readOnly !== false;
}

// --- The artisan pro-forma ------------------------------------------------------------------

export type OversightFile = { blob: Blob; fileName: string };

/**
 * Fetch the .xlsx pro-forma by hand, because `apiFetch` cannot.
 *
 * That helper reads every response as JSON or TEXT, and reading a workbook as text hands back a
 * mangled string cast to the caller's type — a download that "succeeds" and produces a file Excel
 * refuses to open.
 *
 * ⚠ **THIS IS A SECOND COPY OF `lib/questionnaireForms.fetchWorkbook`, AND IT SHOULD NOT BE.** That
 * function is private to its module and this one is character-for-character its three obligations:
 * refuse the request when this build has no usable API address, attach the bearer token, and turn a
 * failure body into the sentence the server actually sent rather than "[object Object]". The right
 * shape is one exported helper both doors call — `lib/questionnaireForms.ts` was owned by another
 * workstream in the wave that wrote this, so the copy is deliberate and temporary rather than an
 * oversight. **If you are the person consolidating them, export `fetchWorkbook` and delete this.**
 */
async function fetchWorkbook(path: string, fallbackName: string): Promise<OversightFile> {
  assertApiConfigured();

  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${API_BASE}/api${path}`, { headers, cache: "no-store" });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json")
      ? await response.json()
      : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload
        ? (payload as { detail: unknown }).detail
        : undefined;
    // `statusText` is empty over HTTP/2 — which every deployed request is — so it can never be the
    // last resort on its own, or a body-less failure reaches the screen as a blank error box.
    throw new ApiError(
      response.status,
      typeof detail === "string" && detail
        ? detail
        : response.statusText || `The server refused the request (HTTP ${response.status}).`,
      payload
    );
  }
  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? fallbackName
  };
}

function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * The blank workbook an officer types a workshop's artisan list into.
 *
 * **PASS THE WORKSHOP ID WHENEVER THERE IS ONE.** With it the Details sheet names the workshop, and
 * the State, District and Venue on that sheet are what blank cells fall back to — so the defaults
 * are VISIBLE in the file rather than applied invisibly at upload time. It is also what the upload
 * checks against the URL, which is what stops fifteen people's regulated records being filed under a
 * stranger's project.
 */
export function downloadArtisanProForma(workshopId?: string | null) {
  return fetchWorkbook(
    `/design-workshop-oversight/artisan-pro-forma${buildQuery({ workshopId: workshopId || undefined })}`,
    "artisan-list-pro-forma.xlsx"
  );
}

/**
 * Upload a filled-in artisan list.
 *
 * MULTIPART WITH ONE PART AND NO SCALARS: the workshop is in the path and everything else is in the
 * workbook. Do not set `Content-Type` — the browser has to write the boundary.
 */
export async function uploadArtisanList(workshopId: string, file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<ArtisanImportReport>(
    `/design-workshop-oversight/${encodeURIComponent(workshopId)}/artisans/upload`,
    { method: "POST", body }
  );
}

/** What a .xlsx door accepts, as an `accept` attribute. A FILTER, never the rule. */
export const ARTISAN_ACCEPT =
  ".xlsx,.xlsm,.xltx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

/** 4 MiB — half the questionnaire's, mirroring `MAX_ARTISAN_UPLOAD_BYTES`. */
export const MAX_ARTISAN_UPLOAD_BYTES = 4 * 1024 * 1024;

/**
 * The refusal for a file this door will not take, or `null`.
 *
 * **BOTH CHECKS ARE THE SERVER'S TOO, AND THAT IS THE POINT RATHER THAN DUPLICATION.** Refusing a
 * 40 MB video in the browser saves an officer on a village connection the upload; the server
 * refuses it again because a client check is not a bound. The wording is the server's so the two
 * doors cannot tell a person two different stories about one file.
 */
export function artisanUploadRefusal(file: File): string | null {
  const name = file.name.toLowerCase();
  if (!/\.(xlsx|xlsm|xltx)$/.test(name)) {
    return `'${file.name}' is not an Excel workbook. Fill in the artisan list pro-forma and upload that, or use File > Save As and choose 'Excel Workbook (.xlsx)'.`;
  }
  if (file.size > MAX_ARTISAN_UPLOAD_BYTES) {
    return "That artisan list is over the 4 MB limit; send a smaller file.";
  }
  if (file.size === 0) {
    return "The upload was empty. Attach the filled-in artisan list pro-forma.";
  }
  return null;
}
