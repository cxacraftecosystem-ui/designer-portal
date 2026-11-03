import { apiFetch } from "@/lib/api";
import type { DwFieldStamp } from "@/lib/designWorkshops";

export type DwCanonicalComparison = {
  stored?: unknown;
  canonical?: unknown;
  diverged?: boolean;
  recordDeleted?: boolean;
};

export function hasMoved(comparison: DwCanonicalComparison | undefined): boolean {
  return Boolean(comparison?.diverged || comparison?.recordDeleted);
}

export const RECORD_DELETED_TEXT = "The record this came from no longer exists";

export function canonicalText(comparison: DwCanonicalComparison): string {
  return comparison.recordDeleted ? RECORD_DELETED_TEXT : comparisonText(comparison.canonical);
}

export type DwProvenanceEntry = {
  entryId: string;
  stageKey: string;
  entityKey: string;
  ordinal?: number | null;
  createdById?: string | null;
  fields?: Record<string, DwFieldStamp>;
  canonical?: Record<string, DwCanonicalComparison>;
};

export type DwProvenanceReport = { entries: DwProvenanceEntry[] };

export async function fetchWorkshopProvenance(workshopId: string): Promise<DwProvenanceReport> {
  return apiFetch<DwProvenanceReport>(`/design-workshops/${workshopId}/provenance`);
}

export function comparisonText(value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

export function divergedFields(entry: DwProvenanceEntry): Array<[string, DwCanonicalComparison]> {
  return Object.entries(entry.canonical ?? {}).filter(([, comparison]) => hasMoved(comparison));
}

export function divergenceTally(report: DwProvenanceReport): { entries: number; fields: number } {
  let entries = 0;
  let fields = 0;
  for (const entry of report.entries ?? []) {
    const diverged = divergedFields(entry);
    if (diverged.length) {
      entries += 1;
      fields += diverged.length;
    }
  }
  return { entries, fields };
}
