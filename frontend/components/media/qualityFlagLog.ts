import type { CapturedFinding } from "@/components/media/photoGate";

const KEY_PREFIX = "field_repo_dw_quality_findings:";

export const MAX_LOGGED_FINDINGS = 200;

function keyFor(workshopId: string): string {
  return `${KEY_PREFIX}${workshopId}`;
}

export function readCaptureFindings(workshopId: string): CapturedFinding[] {
  if (typeof window === "undefined" || !workshopId) return [];
  try {
    const raw = window.localStorage.getItem(keyFor(workshopId));
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((entry): entry is CapturedFinding => {
      if (!entry || typeof entry !== "object") return false;
      const row = entry as Record<string, unknown>;
      return (
        typeof row.mediaId === "string" &&
        row.mediaId.length > 0 &&
        typeof row.fileName === "string" &&
        typeof row.flag === "string" &&
        typeof row.severity === "string" &&
        typeof row.note === "string" &&
        typeof row.raisedAt === "string"
      );
    });
  } catch {
    return [];
  }
}

export function recordCaptureFindings(workshopId: string, findings: CapturedFinding[]): CapturedFinding[] {
  if (typeof window === "undefined" || !workshopId || !findings.length) return readCaptureFindings(workshopId);
  const existing = readCaptureFindings(workshopId);
  const byKey = new Map<string, CapturedFinding>();
  for (const entry of existing) byKey.set(`${entry.mediaId}:${entry.flag}`, entry);
  for (const entry of findings) byKey.set(`${entry.mediaId}:${entry.flag}`, entry);
  const next = [...byKey.values()].slice(-MAX_LOGGED_FINDINGS);
  try {
    window.localStorage.setItem(keyFor(workshopId), JSON.stringify(next));
  } catch {
  }
  return next;
}

export function forgetCaptureFindings(workshopId: string): void {
  if (typeof window === "undefined" || !workshopId) return;
  try {
    window.localStorage.removeItem(keyFor(workshopId));
  } catch {
  }
}
