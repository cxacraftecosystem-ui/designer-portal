import { apiFetch } from "@/lib/api";
import { blankRow, type DwEntryData, type DwRow, type DwValue } from "@/lib/designWorkshops";

export type DwAssistSuggestion = {
  id: string;
  kind: "FILL" | "ADD_ROWS";
  entityKey: string;
  rowKey: string | null;
  title: string;
  detail: string;
  values?: Record<string, DwValue>;
  rows?: Record<string, DwValue>[];
  replaces?: boolean;
  due?: boolean;
};

export type DwAssistSitting = {
  questionnaireId: string;
  title: string;
  count: number;
  latest: string | null;
  respondents: string[];
};

export type DwAssist = {
  stageKey: string;
  suggestions: DwAssistSuggestion[];
  computed: {
    singleton: Record<string, number | null>;
    rows: Record<string, Record<string, Record<string, number | null>>>;
  };
  attendanceDays?: number[];
  startDate?: string | null;
  sittings?: DwAssistSitting[];
};

export function fetchStageAssist(workshopId: string, stageKey: string): Promise<DwAssist> {
  return apiFetch<DwAssist>(`/design-workshops/${workshopId}/stages/${encodeURIComponent(stageKey)}/assist`);
}

const isBlank = (value: DwValue | undefined) =>
  value === null || value === undefined || value === "" || (Array.isArray(value) && value.length === 0);

export function rowMatches(row: DwRow, key: string | null): boolean {
  return key !== null && (row._entryId === key || row._clientKey === key);
}

export function fillValues(suggestion: DwAssistSuggestion, target: DwEntryData | undefined): Record<string, DwValue> {
  const out: Record<string, DwValue> = {};
  for (const [key, value] of Object.entries(suggestion.values ?? {})) {
    if (suggestion.replaces || isBlank(target?.[key])) out[key] = value;
  }
  return out;
}

export function stillApplies(
  suggestion: DwAssistSuggestion,
  singleton: DwEntryData,
  collections: Record<string, DwRow[]>
): boolean {
  if (suggestion.kind === "ADD_ROWS") return true;
  const target =
    suggestion.rowKey === null
      ? singleton
      : (collections[suggestion.entityKey] ?? []).find((row) => rowMatches(row, suggestion.rowKey));
  if (suggestion.rowKey !== null && !target) return false;
  const values = fillValues(suggestion, target);
  if (suggestion.replaces) return Object.entries(values).some(([k, v]) => String(target?.[k] ?? "") !== String(v));
  return Object.keys(values).length > 0;
}

export function suggestedRows(suggestion: DwAssistSuggestion): DwRow[] {
  return (suggestion.rows ?? []).map((values) => ({ ...blankRow(), ...values }));
}

export function usableCheck(check: { key: string; computed: number | null; verdict: string }): boolean {
  return (
    (check.key === "materialCost" || check.key === "labourCost") &&
    check.computed !== null &&
    (check.verdict === "MISMATCH" || check.verdict === "NOT_DECLARED")
  );
}
