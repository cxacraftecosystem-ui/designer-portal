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

export const POLL_MS = 5000;

export const FILES_REMOVED = "This pack's files have been removed after 13 months. Generate it again.";

export const EVERY_SCHEME = "Every scheme";

const BASE = "/ministry-exports";

export function ministryExportsEnabled(user: User | null | undefined): boolean {
  const features = (user as { features?: unknown } | null | undefined)?.features;
  if (!features || typeof features !== "object") return false;
  return (features as Record<string, unknown>).ministryExports === true;
}

export function isActive(status: PackStatus): boolean {
  return status === "QUEUED" || status === "BUILDING";
}

export function shouldPoll(packs: readonly Pick<Pack, "status">[]): boolean {
  return packs.some((pack) => isActive(pack.status));
}

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

export function currentPeriod(now: Date = new Date()): string {
  const ist = new Date(now.getTime() + 330 * 60_000);
  return `${ist.getUTCFullYear()}-${String(ist.getUTCMonth() + 1).padStart(2, "0")}`;
}

export function recentPeriods(count = 13, now: Date = new Date()): string[] {
  const [year, month] = currentPeriod(now).split("-").map(Number);
  const out: string[] = [];
  for (let i = 0; i < count; i += 1) {
    const index = year * 12 + (month - 1) - i;
    out.push(`${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, "0")}`);
  }
  return out;
}

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

export async function downloadPackFile(packId: string, file: PackFile): Promise<void> {
  const link = await mintPackLink(packId, file);
  window.location.assign(link.url);
}
