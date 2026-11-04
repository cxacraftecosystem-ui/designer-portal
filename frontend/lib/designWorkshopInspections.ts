import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import {
  geoValue,
  inputValue,
  isFilled,
  listValue,
  referenceDisplayHint,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwRegistry,
  type DwStageCompleteness,
  type DwStageData,
  type DwSummary,
  type DwValue
} from "@/lib/designWorkshops";
import type { DwCustomDefinition } from "@/lib/customSections";
import { shareInFlight, startNewReadGeneration } from "@/lib/inFlight";
import { forgetMyWorkshopPosts } from "@/lib/myWorkshopPosts";
import { isUnreachable } from "@/lib/offline";
import { canInspectDesignWorkshops, isInspectorTier } from "@/lib/permissions";
import { richSummary } from "@/lib/richText";
import type { DwReadiness } from "@/lib/reviewAids";
import type { PageResult, User, UserRole } from "@/lib/types";

export type DwInspector = {
  userId: string;
  name: string;
  email: string;
  role: UserRole | string;
  assignedAt?: string | null;
};

export type DwInspectorList = { inspectors: DwInspector[] };

export type DwEligibleInspector = {
  id: string;
  name: string;
  email: string;
  role: UserRole | string;
};

export type DwEligibleInspectorList = { users: DwEligibleInspector[]; truncated: boolean };

export const ELIGIBLE_INSPECTOR_SEARCH_MAX = 120;

export const MAX_DESIGN_WORKSHOP_INSPECTORS = 25;

export function fetchAwaitingInspectionCount() {
  return apiFetch<{ awaiting: number }>("/design-workshop-inspections/awaiting-count");
}

export function listDesignWorkshopInspectors(workshopId: string) {
  const path = `/design-workshop-inspections/${workshopId}/inspectors`;
  return shareInFlight(path, () => apiFetch<DwInspectorList>(path));
}

export function putDesignWorkshopInspectors(workshopId: string, userIds: string[]) {
  return apiFetch<DwInspectorList>(`/design-workshop-inspections/${workshopId}/inspectors`, {
    method: "PUT",
    body: JSON.stringify({ userIds })
  }).finally(() => {
    startNewReadGeneration();
    forgetMyWorkshopPosts();
  });
}

export function listEligibleDesignWorkshopInspectors(search?: string) {
  return apiFetch<DwEligibleInspectorList>(
    `/design-workshop-inspections/eligible-inspectors${buildQuery({ search: search?.trim() ?? "" })}`
  );
}

export type DwInspectionDetail = DwSummary & {
  stages: Record<string, DwStageData>;
  completeness: Record<string, DwStageCompleteness>;
  schemaVersion: string;
  customSchemaVersion?: string;
  customSections?: DwCustomDefinition;
  readOnly?: boolean;
  approvedByName?: string | null;
  handedOnByName?: string | null;
};

export type DwInspectableListParams = {
  page?: number;
  pageSize?: number;
  search?: string | null;
  statusFilter?: string | null;
  round?: number | null;
  state?: string | null;
  workshopKind?: string | null;
  dateFrom?: string | null;
  dateTo?: string | null;
  group?: DwInspectionGroup | null;
  sort?: string | null;
};

export type DwInspectionGroup = "awaiting" | "withDesigners" | "other";

export const INSPECTION_GROUPS: ReadonlyArray<{ value: DwInspectionGroup | ""; label: string }> = [
  { value: "awaiting", label: "Waiting for you" },
  { value: "withDesigners", label: "With the designers" },
  { value: "other", label: "Not handed in or closed" },
  { value: "", label: "All" }
];

export type DwInspectableRow = DwSummary & { readiness?: DwReadiness | null };

export type DwInspectablePage = PageResult<DwInspectableRow> & {
  groupCounts?: Partial<Record<DwInspectionGroup, number>>;
  candidatesTruncated?: boolean;
};

export function inspectableListQuery(params: DwInspectableListParams): string {
  const text = (value: string | null | undefined) => (value && value.trim() ? value.trim() : undefined);
  return buildQuery({
    page: params.page,
    pageSize: params.pageSize,
    search: text(params.search),
    statusFilter: text(params.statusFilter),
    round: typeof params.round === "number" && Number.isInteger(params.round) && params.round >= 0 ? params.round : undefined,
    state: text(params.state),
    workshopKind: text(params.workshopKind),
    dateFrom: text(params.dateFrom),
    dateTo: text(params.dateTo),
    group: text(params.group),
    sort: text(params.sort)
  });
}

export function inspectableFilterCount(params: DwInspectableListParams): number {
  return [
    params.statusFilter,
    typeof params.round === "number" ? String(params.round) : "",
    params.state,
    params.workshopKind,
    params.dateFrom,
    params.dateTo
  ].filter((value) => typeof value === "string" && value.trim() !== "").length;
}

export function listInspectableDesignWorkshops(params: DwInspectableListParams) {
  return apiFetch<DwInspectablePage>(`/design-workshop-inspections${inspectableListQuery(params)}`);
}

export function getWorkshopUnderInspection(workshopId: string) {
  return apiFetch<DwInspectionDetail>(`/design-workshop-inspections/${workshopId}`);
}

export function inspectionIsReadOnly(detail: { readOnly?: boolean } | null | undefined): boolean {
  return detail?.readOnly ?? true;
}

export const DESIGN_WORKSHOP_DESTINATIONS = [
  "stages",
  "photos",
  "report",
  "readiness",
  "custom-sections",
  "ai-layers",
  "codes",
  "provenance",
  "sketches-and-prototypes"
] as const;

export type DesignWorkshopDestination = (typeof DESIGN_WORKSHOP_DESTINATIONS)[number];

export function inspectionMayOpen(
  destination: DesignWorkshopDestination,
  detail: { readOnly?: boolean } | null | undefined
): boolean {
  void destination;
  return !inspectionIsReadOnly(detail);
}

export function inspectionAdministrationMissing(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

export function inspectorAdministrationFailure(
  error: unknown,
  fallback: string,
  readerMayAssign: boolean
): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "Couldn't connect, so nothing was changed. Check your connection and try again.";
  }
  if (error.status === 403) {
    return readerMayAssign
      ? `This couldn't be saved. ${error.message}`
      : `This couldn't be saved. ${error.message} Only a Ministry Admin, an Admin or the Master Admin can choose who inspects a workshop.`;
  }
  if (error.status === 409 || error.status === 422) {
    return `This couldn't be saved. ${error.message}`;
  }
  if (error.status === 404) {
    return `${error.message} This workshop may have been deleted. Reload the page to see the current list.`;
  }
  return error.message || fallback;
}

export function inspectionRefusalMeansNoPosts(error: unknown, user: User | null | undefined): boolean {
  return (
    error instanceof ApiError &&
    error.status === 403 &&
    canInspectDesignWorkshops(user) &&
    !isInspectorTier(user)
  );
}

export const INSPECTION_STATUS_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Any status" },
  { value: "DRAFT", label: "Draft" },
  { value: "IN_PROGRESS", label: "In progress" },
  { value: "COMPLETE", label: "Complete" },
  { value: "PRE_SUBMISSION", label: "Pre-submission" },
  { value: "NEEDS_REVISION", label: "Needs revision" },
  { value: "SUBMITTED", label: "Submitted" },
  { value: "APPROVED", label: "Approved" },
  { value: "ARCHIVED", label: "Archived" }
];

export const INSPECTION_ROUND_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "", label: "Any round" },
  { value: "0", label: "Not handed in yet" },
  ...Array.from({ length: 9 }, (_, index) => ({ value: String(index + 1), label: `Round ${index + 1}` }))
];

export function inspectionEmptyState(searched: boolean): { title: string; body: string } {
  return searched
    ? {
        title: "No workshop you inspect matches that search and those filters",
        body: "Only the workshops you have been appointed to inspect are searched. Clear the search and the filters to see them all."
      }
    : {
        title: "You do not hold any inspection posts",
        body: "A Ministry Admin, an Admin or the Master Admin appoints each workshop's inspectors on Workshop oversight. The workshops you are appointed to will appear here."
      };
}

export function eligibleInspectorNotice({
  truncated,
  offered,
  searched
}: {
  truncated: boolean;
  offered: number;
  searched: boolean;
}): string {
  if (truncated && !searched) {
    return "Too many to list — search by name or email.";
  }
  if (truncated) return "Too many matches — narrow the search.";
  if (searched && offered === 0) return "No account that may inspect matches that search.";
  return "";
}

export type InspectionFieldReading =
  | { kind: "text"; text: string }
  | { kind: "empty" }
  | { kind: "media"; count: number };

const MEDIA_FIELD_TYPES = new Set(["IMAGE", "IMAGE_LIST", "FILE", "AUDIO", "VIDEO"]);

function optionLabel(registry: DwRegistry, field: DwField, token: string): string {
  const options = field.options ?? (field.enum ? registry.enums[field.enum] : undefined) ?? [];
  const match = options.find((option) => option.value === token);
  return match ? match.label : token;
}

export function inspectionFieldReading(
  registry: DwRegistry,
  entity: DwEntity,
  field: DwField,
  row: DwEntryData
): InspectionFieldReading {
  const value: DwValue | undefined = row[field.key];

  if (MEDIA_FIELD_TYPES.has(field.type)) {
    const count = Array.isArray(value) ? listValue(value).length : isFilled(value) ? 1 : 0;
    return count > 0 ? { kind: "media", count } : { kind: "empty" };
  }

  if (!isFilled(value)) return { kind: "empty" };

  const withUnit = (text: string) => (field.unit ? `${text} ${field.unit}` : text);

  switch (field.type) {
    case "RICH_TEXT":
      return { kind: "text", text: richSummary(value, 2000) };
    case "BOOL":
      return { kind: "text", text: value === true || inputValue(value) === "true" ? "Yes" : "No" };
    case "ENUM":
      return { kind: "text", text: optionLabel(registry, field, inputValue(value).trim()) };
    case "MULTI_ENUM":
      return {
        kind: "text",
        text: listValue(value)
          .map((token) => optionLabel(registry, field, token))
          .join(" · ")
      };
    case "TAGS":
      return { kind: "text", text: listValue(value).join(" · ") };
    case "GEO": {
      const point = geoValue(value);
      if (!point) return { kind: "empty" };
      const at = `${point.lat.toFixed(6)}, ${point.lon.toFixed(6)}`;
      return {
        kind: "text",
        text: Number.isFinite(point.accuracy) ? `${at} (±${Math.round(point.accuracy as number)} m)` : at
      };
    }
    case "REF": {
      const named = referenceDisplayHint(entity, field, row).trim();
      return named ? { kind: "text", text: named } : { kind: "text", text: "A linked record" };
    }
    default: {
      const text = inputValue(value).trim();
      return text ? { kind: "text", text: withUnit(text) } : { kind: "empty" };
    }
  }
}
