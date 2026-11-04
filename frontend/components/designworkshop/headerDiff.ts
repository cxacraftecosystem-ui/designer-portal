import type { DwSummary } from "@/lib/designWorkshops";

export const EDITABLE_KEYS = [
  "title",
  "templateId",
  "craftName",
  "clusterName",
  "state",
  "district",
  "startDate",
  "endDate",
  "workshopId",
  "notes"
] as const;

export type EditableKey = (typeof EDITABLE_KEYS)[number];

export const EDITABLE_KEY_SET: ReadonlySet<string> = new Set<string>(EDITABLE_KEYS);

export function storedText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

export function changedKeys(onScreen: Record<EditableKey, string>, stored: DwSummary): EditableKey[] {
  return EDITABLE_KEYS.filter((key) => onScreen[key] !== storedText(stored[key]));
}
