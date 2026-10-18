/**
 * Likely-duplicate artisans: the form's check, the save-time hold, and the admin review and merge.
 *
 * The SERVER is the only scorer (`backend/app/services/artisan_duplicates.py`). This file asks it and
 * shapes the answers; nothing here scores a pair. The pure helpers at the bottom are what the unit
 * specs pin (`e2e/duplicates-form-unit.spec.ts`, `e2e/merge-table-unit.spec.ts`).
 */

import { ApiError, apiFetch, buildQuery } from "@/lib/api";

export type DuplicateBand = "VERY_LIKELY" | "LIKELY" | "POSSIBLE";
export type DuplicateReason = { code: string; label: string };

export type DuplicateCandidate = {
  artisan: {
    id: string;
    name: string;
    localName?: string | null;
    place?: string | null;
    state?: string | null;
    district?: string | null;
    craft?: string | null;
    workshop?: string | null;
    birthYear?: number | null;
    photoUrl?: string | null;
    createdAt?: string | null;
  };
  score: number;
  band: DuplicateBand;
  reasons: DuplicateReason[];
};

export type DuplicateCheckRequest = {
  name: string;
  localName?: string | null;
  gender?: string | null;
  dateOfBirth?: string | null;
  phone?: string | null;
  place?: string | null;
  state?: string | null;
  district?: string | null;
  village?: string | null;
  craftId?: string | null;
  craftName?: string | null;
  aadhaar?: string | null;
  pehchanCardNumber?: string | null;
  excludeArtisanId?: string | null;
};

/** 409 `artisan_possible_duplicate` from `POST /artisans`. */
export type PossibleDuplicateHold = {
  code: "artisan_possible_duplicate";
  message: string;
  candidates: DuplicateCandidate[];
  existingArtisan?: { id: string; name: string; place?: string | null; craft?: string | null } | null;
};

export type PairSide = {
  id: string;
  name: string;
  place?: string | null;
  district?: string | null;
  craft?: string | null;
  createdAt?: string | null;
};

export type DuplicatePair = {
  id: string;
  kind: "WITHIN" | "CROSS_PORTAL";
  score: number;
  band: DuplicateBand;
  reasons: DuplicateReason[];
  status: "OPEN" | "NOT_DUPLICATE" | "LINKED";
  source: "SCAN" | "CREATE" | "IMPORT";
  creatorSaidDifferent: boolean;
  a: PairSide | null;
  b: PairSide | null;
  remote: { artisanId: string; matchedBy: string } | null;
  createdAt: string;
};

export type PairPage = {
  items: DuplicatePair[];
  total: number;
  page: number;
  pageSize: number;
  counts: Record<DuplicateBand, number>;
};

export type MergeChoice = "A" | "B" | "BOTH";

export type MergeField = {
  key: string;
  label: string;
  a: string | null;
  b: string | null;
  default: MergeChoice;
  options: MergeChoice[];
  /** The default for each possible survivor (an addition beside `default`). */
  defaults?: { A: MergeChoice; B: MergeChoice };
  locked?: boolean;
};

export type MergeBlocker = {
  code: string;
  message: string;
  action?: { kind: "merge_interviews"; sourceId: string; targetId: string; duplicatePairId?: string };
};

export type RelatedCounts = Record<
  "media" | "products" | "tools" | "workshops" | "designWorkshops" | "interviews" | "comments" | "revisions" | "reviewLogs",
  number
>;

export type PairPreviewSide = {
  id: string;
  updatedAt: string;
  createdAt?: string | null;
  name: string;
  place?: string | null;
  craft?: string | null;
  status?: string | null;
};

export type RemoteDetails =
  | {
      artisanId: string;
      matchedBy: string;
      fields: Record<string, unknown>;
      craft?: { name?: string | null } | null;
      statedAddress?: { state?: string | null; district?: string | null; village?: string | null } | null;
    }
  | { unavailable: true };

export type PairPreview = {
  id: string;
  kind: "WITHIN" | "CROSS_PORTAL";
  score: number;
  band: DuplicateBand;
  reasons: DuplicateReason[];
  status: DuplicatePair["status"];
  a: PairPreviewSide;
  b: PairPreviewSide | null;
  defaultSurvivor?: "A" | "B";
  fields?: MergeField[];
  related?: { a: RelatedCounts; b: RelatedCounts };
  blockers?: MergeBlocker[];
  warnings?: { code: string; message: string; designWorkshopId?: string }[];
  crossPortal?: { a: { remoteArtisanId: string } | null; b: { remoteArtisanId: string } | null };
  remote?: RemoteDetails;
};

export type MergeResult = {
  merge: { id: string; survivorId: string; mergedId: string; undoUntil: string };
  artisan: { id: string; name: string };
  warnings: { code: string; message: string }[];
};

export type MergeRecord = {
  id: string;
  survivor: { id: string; name: string | null };
  merged: { id: string; name: string | null };
  mergedBy: { id: string | null; name: string | null };
  createdAt: string;
  undoUntil: string;
  undoneAt: string | null;
  canUndo: boolean;
};

export type UndoResult = {
  merge: { id: string; undoneAt: string };
  restoredArtisanId: string;
  keptSurvivorEdits: string[];
};

// ── Requests ────────────────────────────────────────────────────────────────────────────────

const post = (body: unknown) => ({ method: "POST", body: JSON.stringify(body) });

export async function checkDuplicates(request: DuplicateCheckRequest): Promise<DuplicateCandidate[]> {
  const result = await apiFetch<{ candidates: DuplicateCandidate[] }>("/artisan-duplicates/check", post(request));
  return Array.isArray(result?.candidates) ? result.candidates : [];
}

export function listPairs(params: {
  status?: DuplicatePair["status"];
  kind?: DuplicatePair["kind"] | "";
  band?: DuplicateBand | "";
  artisanId?: string;
  page?: number;
  pageSize?: number;
}): Promise<PairPage> {
  return apiFetch<PairPage>(`/artisan-duplicates${buildQuery(params)}`);
}

export function getPair(id: string): Promise<PairPreview> {
  return apiFetch<PairPreview>(`/artisan-duplicates/${encodeURIComponent(id)}`);
}

export function mergePair(
  id: string,
  body: {
    survivor: "A" | "B";
    choices: Record<string, MergeChoice>;
    expectedUpdatedAt: { a: string; b: string };
    reason?: string | null;
  }
): Promise<MergeResult> {
  return apiFetch<MergeResult>(`/artisan-duplicates/${encodeURIComponent(id)}/merge`, post(body));
}

export function dismissPair(id: string, note: string | null = null): Promise<DuplicatePair> {
  return apiFetch<DuplicatePair>(`/artisan-duplicates/${encodeURIComponent(id)}/dismiss`, post({ note }));
}

export function reopenPair(id: string): Promise<DuplicatePair> {
  return apiFetch<DuplicatePair>(`/artisan-duplicates/${encodeURIComponent(id)}/reopen`, post({}));
}

export function linkPair(id: string): Promise<unknown> {
  return apiFetch(`/artisan-duplicates/${encodeURIComponent(id)}/link`, post({ consent: true }));
}

export function mergeInterviews(sourceId: string, targetId: string, duplicatePairId?: string): Promise<unknown> {
  const query = duplicatePairId ? buildQuery({ duplicatePairId }) : "";
  return apiFetch(
    `/questionnaire/interviews/${encodeURIComponent(sourceId)}/merge-into/${encodeURIComponent(targetId)}${query}`,
    post({})
  );
}

export function listMerges(page = 1, pageSize = 20): Promise<{ items: MergeRecord[]; total: number; page: number; pageSize: number }> {
  return apiFetch(`/artisan-merges${buildQuery({ page, pageSize })}`);
}

export function undoMerge(id: string): Promise<UndoResult> {
  return apiFetch<UndoResult>(`/artisan-merges/${encodeURIComponent(id)}/undo`, post({}));
}

// ── Pure helpers ────────────────────────────────────────────────────────────────────────────

/** The fields a check depends on, as one comparable string: a blur re-checks only when it moved. */
export function checkKey(request: DuplicateCheckRequest): string {
  const norm = (value: string | null | undefined) => (value ?? "").trim().toLowerCase().replace(/\s+/g, " ");
  return [
    norm(request.name),
    norm(request.state),
    norm(request.district),
    (request.phone ?? "").replace(/\D/g, "").slice(-10),
    norm(request.dateOfBirth),
    norm(request.aadhaar),
    norm(request.pehchanCardNumber)
  ].join("|");
}

/** Whether a check is worth sending: a name of two letters or more. */
export function checkReady(request: DuplicateCheckRequest): boolean {
  return (request.name ?? "").trim().length >= 2;
}

/** The candidates still to show: not already set aside as "Different person", best first, at most 3. */
export function visibleCandidates(candidates: DuplicateCandidate[], dismissed: ReadonlySet<string>, limit = 3): DuplicateCandidate[] {
  return candidates
    .filter((candidate) => !dismissed.has(candidate.artisan.id))
    .sort((left, right) => right.score - left.score)
    .slice(0, limit);
}

/**
 * The `duplicateAcknowledged` a create sends: the ids set aside as "Different person", or `[]` when
 * nothing was. Always a list on a create (a list is what asks the server to hold), never on an edit.
 */
export function acknowledgedIds(dismissed: ReadonlySet<string>): string[] {
  return Array.from(dismissed).slice(0, 20);
}

/** One line under a candidate's name: place · craft · born, with whatever is known. */
export function candidateLine(candidate: DuplicateCandidate): string {
  const a = candidate.artisan;
  return [a.place || a.district, a.craft, a.birthYear ? `born ${a.birthYear}` : null].filter(Boolean).join(" · ");
}

/** The hold behind a 409, or null when the failure was something else. */
export function possibleDuplicateHold(error: unknown): PossibleDuplicateHold | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const payload = error.payload as { detail?: unknown } | null | undefined;
  const detail = payload && typeof payload === "object" ? payload.detail : null;
  if (!detail || typeof detail !== "object") return null;
  const hold = detail as PossibleDuplicateHold;
  return hold.code === "artisan_possible_duplicate" && Array.isArray(hold.candidates) ? hold : null;
}

/** The survivor of `GET /artisans/{id}`'s 404 `artisan_merged`, or null. */
export function mergedSurvivorId(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 404) return null;
  const payload = error.payload as { detail?: unknown } | null | undefined;
  const detail = payload && typeof payload === "object" ? (payload.detail as { code?: string; survivorId?: string } | null) : null;
  return detail && detail.code === "artisan_merged" && typeof detail.survivorId === "string" ? detail.survivorId : null;
}

/** The default side of every field for a survivor. */
export function defaultChoices(fields: MergeField[], survivor: "A" | "B"): Record<string, MergeChoice> {
  const out: Record<string, MergeChoice> = {};
  for (const field of fields) {
    if (field.locked) {
      out[field.key] = field.default;
      continue;
    }
    out[field.key] = field.defaults?.[survivor] ?? (field.a == null && field.b != null ? "B" : field.b == null && field.a != null ? "A" : survivor);
  }
  return out;
}

/** The value a field will end with, for the preview column. */
export function resultingValue(field: MergeField, choice: MergeChoice): string | null {
  if (choice === "BOTH") return [field.a, field.b].filter((v) => v && v.trim()).join("\n") || null;
  return choice === "A" ? field.a : field.b;
}

/** What the merge call sends: only the choosable fields, locked rows left to the server. */
export function choicesPayload(fields: MergeField[], picks: Record<string, MergeChoice>): Record<string, MergeChoice> {
  const out: Record<string, MergeChoice> = {};
  for (const field of fields) {
    if (field.locked) continue;
    const pick = picks[field.key];
    if (pick && field.options.includes(pick)) out[field.key] = pick;
  }
  return out;
}

export const BAND_LABEL: Record<DuplicateBand, string> = {
  VERY_LIKELY: "Very likely",
  LIKELY: "Likely",
  POSSIBLE: "Possible"
};
