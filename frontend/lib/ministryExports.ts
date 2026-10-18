/**
 * THE MINISTRY'S MONTHLY EXPORT PACKS — types, calls and the pure rules `/ministry-exports` draws by.
 *
 * Server: `backend/app/api/routes/ministry_exports.py` (every route 404 while
 * `MINISTRY_EXPORTS_ENABLED` is off) over `backend/app/services/ministry_exports.py`. The web learns
 * whether the feature is on from the session payload's `features.ministryExports`, the same key
 * `GET /api/features` answers, so all three entry points (nav, admin hub tile, ministry dashboard
 * header) hide while it is off.
 *
 * A download is minted per click: `POST /packs/{id}/links` answers a five-minute presigned URL and
 * logs the download; the browser is then sent to it. The URL is never stored and never e-mailed.
 */

import { apiFetch } from "@/lib/api";
import type { User } from "@/lib/types";

export type PackStatus = "QUEUED" | "BUILDING" | "READY" | "EMPTY" | "FAILED";
export type PackFile = "xlsx" | "pdf";

export type PackCounts = {
  workshops: number;
  registered: number;
  ongoing: number;
  completed: number;
  createdInPeriod: number;
  handedInInPeriod: number;
  approvedInPeriod: number;
  handedOnInPeriod: number;
  participants: number;
  /** A Decimal, so a string on the wire. */
  sanctionedAmount: string;
  truncated: boolean;
};

export type Pack = {
  id: string;
  period: string;
  periodLabel: string;
  scheme: string;
  schemeLabel: string;
  revision: number;
  trigger: "SCHEDULED" | "ON_DEMAND";
  status: PackStatus;
  monthToDate: boolean;
  snapshotAt: string | null;
  finishedAt: string | null;
  counts: PackCounts | null;
  files: Record<PackFile, { name: string; sizeBytes: number | null }> | null;
  filesExpireOn: string | null;
  requestedBy: { id: string; name: string } | null;
  error: string | null;
  mail: { queued: number; sent: number; failed: number };
};

export type Scheme = { key: string; label: string; workshopCount: number };

export type Recipient = {
  userId: string;
  name: string | null;
  email: string | null;
  role: string | null;
  schemes: string[];
  active: boolean;
  eligible: boolean;
  ineligibleReason?: string | null;
  updatedAt: string | null;
};

export type EligibleAccount = { userId: string; name: string; email: string; role: string };

export type PackLink = { url: string; expiresAt: string; fileName: string };

/** How often the page re-reads while any pack is QUEUED or BUILDING. */
export const POLL_MS = 5000;

/** The 410 a link answers once the bucket's lifecycle rule has removed a pack's files — the server's words. */
export const FILES_REMOVED = "This pack's files have been removed after 13 months. Generate it again.";

/** What an empty scheme list means, on a recipient and in the generator. */
export const EVERY_SCHEME = "Every scheme";

const BASE = "/ministry-exports";

// ── feature switch ───────────────────────────────────────────────────────────────────────────────

/** Whether this deployment has the packs switched on, read off the session payload. */
export function ministryExportsEnabled(user: User | null | undefined): boolean {
  const features = (user as { features?: unknown } | null | undefined)?.features;
  if (!features || typeof features !== "object") return false;
  return (features as Record<string, unknown>).ministryExports === true;
}

// ── pure rules ───────────────────────────────────────────────────────────────────────────────────

export function isActive(status: PackStatus): boolean {
  return status === "QUEUED" || status === "BUILDING";
}

/** Keep polling while anything is still being built; stop the moment everything is terminal. */
export function shouldPoll(packs: readonly Pick<Pack, "status">[]): boolean {
  return packs.some((pack) => isActive(pack.status));
}

/**
 * The controls one pack card draws. Excel and PDF only when READY; Regenerate once it has finished
 * (READY, EMPTY or FAILED) — never while it is queued or building, which the server would ignore.
 */
export function packControls(pack: Pick<Pack, "status" | "files">): { download: boolean; regenerate: boolean } {
  return { download: pack.status === "READY" && pack.files !== null, regenerate: !isActive(pack.status) };
}

export const STATUS_WORDS: Record<PackStatus, string> = {
  QUEUED: "Queued",
  BUILDING: "Building",
  READY: "Ready",
  EMPTY: "No workshops",
  FAILED: "Failed"
};

/** "Every scheme" for an empty selection, else the labels in registry order. */
export function schemesSummary(selected: readonly string[], schemes: readonly Pick<Scheme, "key" | "label">[]): string {
  if (selected.length === 0) return EVERY_SCHEME;
  const labels = schemes.filter((s) => selected.includes(s.key)).map((s) => s.label);
  return labels.length ? labels.join(", ") : EVERY_SCHEME;
}

const PERIOD = /^(\d{4})-(0[1-9]|1[0-2])$/;
const MONTHS = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December"
];

export function isPeriod(value: string | null | undefined): value is string {
  return !!value && PERIOD.test(value);
}

export function periodLabel(period: string): string {
  const found = PERIOD.exec(period);
  if (!found) return period;
  return `${MONTHS[Number(found[2]) - 1]} ${found[1]}`;
}

/** The current month in India time ("YYYY-MM"), whatever the browser's own zone. */
export function currentPeriod(now: Date = new Date()): string {
  const ist = new Date(now.getTime() + 330 * 60_000);
  return `${ist.getUTCFullYear()}-${String(ist.getUTCMonth() + 1).padStart(2, "0")}`;
}

/** The current month and the `count - 1` before it, newest first — the generator's choices. */
export function recentPeriods(count = 13, now: Date = new Date()): string[] {
  const [year, month] = currentPeriod(now).split("-").map(Number);
  const out: string[] = [];
  for (let i = 0; i < count; i += 1) {
    const index = year * 12 + (month - 1) - i;
    out.push(`${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, "0")}`);
  }
  return out;
}

/**
 * Which month the page opens on: `?period=` when it is a real month (the e-mail's link), else the
 * newest month that has packs, else last month.
 */
export function initialPeriod(query: string | null, periods: readonly string[], now: Date = new Date()): string {
  if (isPeriod(query)) return query;
  if (periods.length) return periods[0];
  return recentPeriods(2, now)[1];
}

export function sizeText(bytes: number | null | undefined): string {
  if (!bytes || bytes <= 0) return "";
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// ── calls ────────────────────────────────────────────────────────────────────────────────────────

export function fetchSchemes(): Promise<{ schemes: Scheme[] }> {
  return apiFetch(`${BASE}/schemes`);
}

export function fetchPacks(period?: string): Promise<{ items: Pack[]; periods: string[] }> {
  const query = period ? `?period=${encodeURIComponent(period)}&limit=50` : "?limit=50";
  return apiFetch(`${BASE}/packs${query}`);
}

export function generatePacks(period: string, schemes: string[], idempotencyKey?: string): Promise<{ items: Pack[] }> {
  const headers: Record<string, string> = {};
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  return apiFetch(`${BASE}/packs`, { method: "POST", headers, body: JSON.stringify({ period, schemes }) });
}

export function fetchRecipients(): Promise<{ recipients: Recipient[] }> {
  return apiFetch(`${BASE}/recipients`);
}

export function searchEligibleAccounts(search: string): Promise<{ accounts: EligibleAccount[] }> {
  const query = search.trim() ? `?search=${encodeURIComponent(search.trim())}` : "";
  return apiFetch(`${BASE}/eligible-accounts${query}`);
}

export function saveRecipient(userId: string, schemes: string[], active: boolean): Promise<Recipient> {
  return apiFetch(`${BASE}/recipients/${encodeURIComponent(userId)}`, {
    method: "PUT",
    body: JSON.stringify({ schemes, active })
  });
}

export function removeRecipient(userId: string): Promise<void> {
  return apiFetch(`${BASE}/recipients/${encodeURIComponent(userId)}`, { method: "DELETE" });
}

export function mintPackLink(packId: string, file: PackFile): Promise<PackLink> {
  return apiFetch(`${BASE}/packs/${encodeURIComponent(packId)}/links`, {
    method: "POST",
    body: JSON.stringify({ file })
  });
}

/**
 * Mint a link and send the browser to it. Not `lib/fileDownload`: that helper fetches the bytes
 * through the API with the bearer token, and these bytes come straight from storage on a signed URL
 * that carries its own `Content-Disposition`, so a navigation is the whole download.
 */
export async function downloadPackFile(packId: string, file: PackFile): Promise<void> {
  const link = await mintPackLink(packId, file);
  window.location.assign(link.url);
}
