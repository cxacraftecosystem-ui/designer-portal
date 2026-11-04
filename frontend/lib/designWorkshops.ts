import { API_BASE, ApiError, apiFetch, assertApiConfigured, buildQuery, describeApiDetail } from "@/lib/api";
import { prepareIdentityPhotograph } from "@/lib/identityCardImage";
import { DW_PHOTO_RETENTION_DEFAULT, type DwPhotoRetention } from "@/lib/identityPhotoRetention";
import type { CostFindingsPayload } from "@/lib/costIntegrity";
import { pyStrip, type MarketFindingsPayload } from "@/lib/marketAnalysis";
import type { DwReportHistory } from "@/lib/reportDiff";
import { isStoredRichTextEmpty, richSummary, type StoredRichDoc } from "@/lib/richText";
import type { PageResult } from "@/lib/types";
import { exportFilename } from "@/lib/naming";

export type DwFieldType =
  | "TEXT"
  | "LONG_TEXT"
  | "RICH_TEXT"
  | "INT"
  | "DECIMAL"
  | "MONEY"
  | "PERCENT"
  | "DATE"
  | "TIME"
  | "BOOL"
  | "ENUM"
  | "MULTI_ENUM"
  | "TAGS"
  | "IMAGE"
  | "IMAGE_LIST"
  | "FILE"
  | "AUDIO"
  | "VIDEO"
  | "GEO"
  | "REF"
  | "URL"
  | "PHONE"
  | "EMAIL";

export const UNSUPPORTED_FIELD_TYPE_PREFIX = "UNSUPPORTED:";

export function unsupportedFieldType(raw: string): DwFieldType {
  return `${UNSUPPORTED_FIELD_TYPE_PREFIX}${raw}` as DwFieldType;
}

export function fieldTypeName(type: string): string {
  return type.startsWith(UNSUPPORTED_FIELD_TYPE_PREFIX)
    ? type.slice(UNSUPPORTED_FIELD_TYPE_PREFIX.length)
    : type;
}

export type DwTier = "BASIC" | "STANDARD" | "ADVANCED";

export type DwReportRole =
  | "NARRATIVE"
  | "KEY_VALUE"
  | "TABLE_COLUMN"
  | "CAPTION"
  | "GALLERY"
  | "COVER_FIELD"
  | "METRIC"
  | "BULLETS"
  | "HIDDEN";

export type DwTextFormat = "EMAIL" | "PHONE_IN" | "AADHAAR" | "PEHCHAN" | "PINCODE";

export type DwCardinality = "SINGLETON" | "COLLECTION";

export type DwEnumOption = { value: string; label: string };

export const DW_DEFAULT_MAX_ITEMS = 200;

export type DwField = {
  key: string;
  label: string;
  type: DwFieldType;
  tier: DwTier;
  required: boolean;
  help?: string;
  details?: string;
  unit?: string;
  enum?: string;
  options?: DwEnumOption[];
  refModel?: string;
  refScope?: string;
  refFilterBy?: string;
  maxLength?: number;
  maxItems?: number;
  storeMasked?: boolean;
  format?: DwTextFormat;
  minValue?: number;
  maxValue?: number;
  reportRole?: DwReportRole;
  derivedKind?: "DAYS_BETWEEN" | "PRODUCT" | "SUM";
  derivedFrom?: string[];
  autoNumber?: string;
  autoNumberWithin?: string;
  autoDateFromDay?: string;
  computedKind?: "ROSTER_COUNT" | "ROSTER_WOMEN" | "ATTENDANCE_DAYS" | "ROSTER_COMPLETED" | "SURVEY_RESPONSES";
  widget?: string;
  refSuggest?: { fill: Record<string, string>; hint: Record<string, string> };
  columnWidthPct?: number;
  captionFor?: string;
  deprecated?: boolean;
  replacedBy?: string;
};

export type DwEntity = {
  key: string;
  name: string;
  cardinality: DwCardinality;
  title: string;
  description: string;
  parent: string;
  labelField: string;
  fields: DwField[];
};

export type DwStage = {
  number: number;
  key: string;
  title: string;
  purpose: string;
  notes: string;
  optionalStage: boolean;
  entities: DwEntity[];
};

export type DwRegistry = {
  version: string;
  enums: Record<string, DwEnumOption[]>;
  stages: DwStage[];
};

export type DwGeoValue = { lat: number; lon: number; accuracy?: number };

export type DwValue = string | number | boolean | string[] | DwGeoValue | StoredRichDoc | null;

export type DwEntryData = Record<string, DwValue | undefined>;

export type DwRow = DwEntryData & {
  _entryId?: string;
  _ordinal?: number;
  _clientKey?: string;
};

export type DwStageCompleteness = {
  stageKey: string;
  number: number;
  title: string;
  requiredTotal: number;
  requiredFilled: number;
  optionalTotal: number;
  optionalFilled: number;
  percent: number;
  isComplete: boolean;
  collectionCounts: Record<string, number>;
  missing: string[];
};

export type DwFieldStamp = {
  by?: string | null;
  byName?: string | null;
  at?: string;
  source?: string;
  refModel?: string | null;
  refId?: string | null;
  refKey?: string | null;
  copiedFrom?: { workshopId: string; title: string } | null;
};

export type DwStageProvenance = {
  singleton?: Record<string, DwFieldStamp>;
  collections?: Record<string, Record<string, Record<string, DwFieldStamp>>>;
  custom?: Record<string, DwFieldStamp>;
};

export type DwStageData = {
  singleton: DwEntryData;
  collections: Record<string, DwRow[]>;
  custom?: DwEntryData;
  provenance?: DwStageProvenance;
  completeness?: DwStageCompleteness | null;
  customSchemaVersion?: string;
};

export type DwStatus =
  | "DRAFT"
  | "IN_PROGRESS"
  | "COMPLETE"
  | "PRE_SUBMISSION"
  | "NEEDS_REVISION"
  | "SUBMITTED"
  | "APPROVED"
  | "ARCHIVED";

export const DW_LEGAL_TRANSITIONS: Record<DwStatus, DwStatus[]> = {
  DRAFT: ["IN_PROGRESS", "COMPLETE", "PRE_SUBMISSION", "ARCHIVED"],
  IN_PROGRESS: ["COMPLETE", "PRE_SUBMISSION", "ARCHIVED"],
  COMPLETE: ["IN_PROGRESS", "PRE_SUBMISSION", "ARCHIVED"],
  PRE_SUBMISSION: ["IN_PROGRESS", "NEEDS_REVISION", "APPROVED"],
  NEEDS_REVISION: ["IN_PROGRESS", "PRE_SUBMISSION"],
  SUBMITTED: ["IN_PROGRESS", "PRE_SUBMISSION", "ARCHIVED", "NEEDS_REVISION"],
  APPROVED: ["SUBMITTED", "NEEDS_REVISION"],
  ARCHIVED: ["IN_PROGRESS", "PRE_SUBMISSION"]
};

export const DW_DECISION_EDGES: ReadonlyArray<readonly [DwStatus, DwStatus]> = [
  ["PRE_SUBMISSION", "NEEDS_REVISION"],
  ["PRE_SUBMISSION", "APPROVED"],
  ["APPROVED", "NEEDS_REVISION"],
  ["APPROVED", "SUBMITTED"],
  ["SUBMITTED", "NEEDS_REVISION"]
];

export function dwPatchableFrom(status: string, options?: { handedOn?: boolean }): DwStatus[] {
  if (options?.handedOn && status === "SUBMITTED") return [];
  const from = DW_LEGAL_TRANSITIONS[status as DwStatus];
  if (!from) return [];
  return from.filter(
    (next) => !DW_DECISION_EDGES.some(([a, b]) => a === (status as DwStatus) && b === next)
  );
}

export const DW_FROZEN_APPROVED =
  "Approved and locked. To change it, ask the Ministry Admin to withdraw the approval.";
export const DW_FROZEN_HANDED_ON =
  "Handed on to the office and locked. To change it, ask the Ministry Admin to return it.";

export function dwIsFrozen(row: { status?: string | null; handedOnAt?: string | null }): boolean {
  const status = String(row.status ?? "").trim().toUpperCase();
  return status === "APPROVED" || (status === "SUBMITTED" && Boolean(row.handedOnAt));
}

export function dwFrozenReason(row: { status?: string | null; handedOnAt?: string | null }): string | null {
  if (!dwIsFrozen(row)) return null;
  return String(row.status ?? "").trim().toUpperCase() === "APPROVED" ? DW_FROZEN_APPROVED : DW_FROZEN_HANDED_ON;
}

export type DwSummary = {
  id: string;
  title: string;
  templateId: string;
  status: DwStatus | string;
  workshopCode: string | null;
  workshopKind: string | null;
  scheme: string | null;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  district: string | null;
  venue: string | null;
  startDate: string | null;
  endDate: string | null;
  designerName: string | null;
  implementingAgency: string | null;
  sponsor: string | null;
  notes: string | null;
  workshopId: string | null;
  dictationConsent: "NOT_RECORDED" | "GRANTED" | "REFUSED" | string;
  dictationConsentAt: string | null;
  dictationConsentById: string | null;
  createdById: string;
  createdAt: string | null;
  updatedAt: string | null;
  deletedAt: string | null;
  reviewNotes?: string | null;
  reviewedById?: string | null;
  reviewedAt?: string | null;
  submissionRound?: number;
  approvedById?: string | null;
  approvedAt?: string | null;
  approvedRound?: number | null;
  handedOnById?: string | null;
  handedOnAt?: string | null;
  handedOnTo?: string | null;
  handedOnExportId?: string | null;
  lastHandedInAt?: string | null;
  deletedById?: string | null;
  deletedByName?: string | null;
};

export type DwInspectionFeedback = {
  id: string;
  designWorkshopId: string;
  round: number;
  stageKey: string | null;
  fieldKey: string | null;
  note: string;
  sentBack: boolean;
  byApprovingAuthority?: boolean;
  actorId: string;
  actorName: string | null;
  recordedAt: string | null;
  createdAt: string | null;
};

export type DwDecisionKind = "APPROVED" | "APPROVAL_WITHDRAWN" | "RETURNED" | "SENT_BACK" | "HANDED_ON";

export type DwDecision = {
  id: string;
  kind: DwDecisionKind | string;
  note: string | null;
  actorId: string;
  actorName: string | null;
  at: string | null;
};

export type DwInspectionFeedbackKeys = {
  inspectionFeedback?: DwInspectionFeedback[];
  inspectionFeedbackTruncated?: boolean;
  mayRecordFeedback?: boolean;
};

export function dwMayRecordFeedback(detail: DwInspectionFeedbackKeys | null | undefined): boolean {
  return detail?.mayRecordFeedback === true;
}

export function dwFeedbackRounds(
  rows: DwInspectionFeedback[] | undefined | null
): Array<{ round: number; rows: DwInspectionFeedback[] }> {
  const byRound = new Map<number, DwInspectionFeedback[]>();
  for (const row of rows ?? []) {
    const bucket = byRound.get(row.round) ?? [];
    bucket.push(row);
    byRound.set(row.round, bucket);
  }
  return [...byRound.entries()]
    .sort((a, b) => b[0] - a[0])
    .map(([round, group]) => ({ round, rows: group }));
}

export type DwDetail = DwSummary &
  DwInspectionFeedbackKeys & {
    reviewNotes: string | null;
    reviewedById: string | null;
    reviewedAt: string | null;
    submissionRound: number;
  dictationConsentByName?: string | null;
  approvedByName?: string | null;
  approvedByRole?: string | null;
  handedOnByName?: string | null;
  decisions?: DwDecision[];
  stages: Record<string, DwStageData>;
  completeness: Record<string, DwStageCompleteness>;
  schemaVersion: string;
  customSchemaVersion?: string;
};

export type DwStagesPayload = {
  stages: Record<string, DwStageData>;
  completeness: Record<string, DwStageCompleteness>;
  schemaVersion: string;
  customSchemaVersion?: string;
};

export type DwTemplate = { id: string; name: string; description: string };

export type DwCreateBody = {
  title: string;
  templateId?: string;
  workshopKind?: string | null;
  designerUserId?: string | null;
  designerUserIds?: string[] | null;
  craftName?: string | null;
  clusterName?: string | null;
  state?: string | null;
  district?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  workshopId?: string | null;
  notes?: string | null;
};

export type DwUpdateBody = Omit<Partial<DwCreateBody>, "designerUserId" | "designerUserIds"> & {
  status?: string;
};

export const MAX_NAMED_DESIGNERS = 100;

export function namedDesignerTeam({ chosen, lead }: { chosen: readonly string[]; lead: string }): {
  lead: string;
  team: string[];
} {
  const ticked: string[] = [];
  const seen = new Set<string>();
  for (const raw of chosen) {
    const id = (raw ?? "").trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    ticked.push(id);
  }
  const preferred = (lead ?? "").trim();
  if (ticked.length === 0) return preferred ? { lead: preferred, team: [preferred] } : { lead: "", team: [] };
  const resolved = preferred && seen.has(preferred) ? preferred : ticked[0];
  return { lead: resolved, team: [resolved, ...ticked.filter((id) => id !== resolved)] };
}

export function designerCreateFields({ chosen, lead }: { chosen: readonly string[]; lead: string }): {
  designerUserId?: string;
  designerUserIds?: string[];
} {
  const resolved = namedDesignerTeam({ chosen, lead });
  if (resolved.team.length === 0) return {};
  if (resolved.team.length === 1) return { designerUserId: resolved.lead };
  return { designerUserId: resolved.lead, designerUserIds: resolved.team };
}

export type DwSaveEntry = {
  entityKey: string;
  entryId?: string;
  ordinal?: number;
  data: DwEntryData;
  merge?: boolean;
};

export type DwSaveBody = {
  entries: DwSaveEntry[];
  replaceCollections?: boolean;
  emptiedEntities?: string[];
  submit?: boolean;
  copiedFrom?: Record<string, string>;
};

export type DwSaveResult = {
  stageKey: string;
  saved: number;
  created: number;
  updated: number;
  removed: number;
  errors: Record<string, Record<string, string>>;
  refusedAnswers?: number;
  droppedKeys: string[];
  droppedCustomKeys?: string[];
  completeness: DwStageCompleteness | null;
  schemaVersion: string;
  customSchemaVersion?: string;
};

export type DwExport = {
  id: string;
  format: string;
  templateId: string;
  fileName: string;
  fileSizeBytes: number | string | null;
  pageCount: number | null;
  checksumSha256: string | null;
  generatedOnDevice: boolean;
  generatedAt: string | null;
  warnings: string | null;
};

export type DwExportRecordBody = {
  format: string;
  templateId: string;
  fileName: string;
  generatedAt: string;
  fileSizeBytes?: number | null;
  pageCount?: number | null;
  checksumSha256?: string | null;
  warnings?: string | null;
};

export type DwRun = {
  text: string;
  bold: boolean;
  italic: boolean;
  underline?: boolean;
  strike?: boolean;
  script: string;
  color: string | null;
};

export type DwImageRef = {
  source: string;
  width_px: number;
  height_px: number;
  rotation_deg: number;
  mime_type: string;
};

export type DwTableColumn = {
  header: string;
  width_pct: number;
  align: string;
  numeric: boolean;
};

export type DwBlock =
  | {
      type: "COVER";
      title: string;
      subtitle: string;
      org_lines: string[];
      logo: DwImageRef | null;
      hero_image: DwImageRef | null;
      info_rows: Array<[string, string]>;
      footer_lines: string[];
    }
  | { type: "TOC"; title: string; depth: number }
  | { type: "HEADING"; level: number; runs: DwRun[]; number: string; bookmark: string }
  | { type: "PARAGRAPH"; runs: DwRun[]; style: string; align: string }
  | { type: "BULLETLIST"; items: DwRun[][]; ordered: boolean }
  | { type: "KEYVALUE"; pairs: Array<[string, DwRun[]]>; columns: number; label_width_pct: number }
  | {
      type: "TABLE";
      columns: DwTableColumn[];
      rows: DwRun[][][];
      caption: string;
      total_row: DwRun[][] | null;
      zebra: boolean;
    }
  | { type: "IMAGE"; image: DwImageRef; width_pct: number; align: string; caption: string }
  | { type: "IMAGEGRID"; images: Array<[DwImageRef, string]>; columns: number; caption: string }
  | { type: "METRICROW"; metrics: Array<[string, string, string]> }
  | { type: "CALLOUT"; kind: string; title: string; runs: DwRun[] }
  | { type: "SIGNATURE"; signatories: Array<[string, string]> }
  | { type: "SPACER"; height_pct: number }
  | { type: "PAGEBREAK" };

export type DwPreview = {
  meta: {
    title: string;
    subtitle: string;
    templateId: string;
    templateName: string;
    pageSize: string;
  };
  blocks: DwBlock[];
  warnings: string[];
};

export type DwReportBody = {
  templateId?: string | null;
  formats: Array<"DOCX" | "PDF">;
  pageSize?: string | null;
  themeAccent?: string | null;
  headerText?: string | null;
  footerText?: string | null;
  includePhotographs?: boolean | null;
  includeTranscripts?: boolean | null;
  includeAiLayers?: boolean;
  record?: boolean;
  fresh?: boolean;
};

export type DwTranscriptItem = {
  mediaId: string;
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  fieldKey: string;
  fieldLabel: string;
  label: string;
  filename: string;
  recordedAt: string;
  durationSeconds: number | null;
  durationText: string;
  speakerCount: number;
  wordCount: number;
  firstLine: string;
  status: string;
  hasTranscript: boolean;
  includedInReport: boolean;
};

export type DwTranscriptList = {
  items: DwTranscriptItem[];
  total: number;
  withTranscript: number;
  totalDurationSeconds: number | null;
};

export function listDesignWorkshopTranscripts(id: string) {
  return apiFetch<DwTranscriptList>(`/design-workshops/${id}/transcripts`);
}

let cachedRegistry: DwRegistry | null = null;
let registryInFlight: Promise<DwRegistry> | null = null;

export async function fetchStageRegistry(options?: { refresh?: boolean }): Promise<DwRegistry> {
  if (!options?.refresh && cachedRegistry) return cachedRegistry;
  if (!options?.refresh && registryInFlight) return registryInFlight;

  const request = apiFetch<DwRegistry>("/design-workshops/schema", {}, { revalidateFromHttpCache: true })
    .then((next) => {
      if (cachedRegistry && cachedRegistry.version === next.version) return cachedRegistry;
      cachedRegistry = next;
      return next;
    })
    .finally(() => {
      registryInFlight = null;
    });

  registryInFlight = request;
  return request;
}

export function peekStageRegistry(): DwRegistry | null {
  return cachedRegistry;
}

export const WORKSHOP_KIND_FLOOR: readonly DwEnumOption[] = [
  { value: "DESIGN_PROTOTYPE_DEVELOPMENT", label: "Design & Prototype Development" },
  { value: "SKILL_UPGRADATION", label: "Skill Upgradation" },
  { value: "DESIGN_INTERVENTION", label: "Design Intervention" },
  { value: "CLUSTER_DEVELOPMENT", label: "Cluster Development" },
  { value: "EXPOSURE_EXHIBITION", label: "Exposure / Exhibition" },
  { value: "OTHER", label: "Other" }
];

export function workshopKindOptions(registry: DwRegistry | null): {
  options: readonly DwEnumOption[];
  served: boolean;
} {
  const served = registry?.enums?.WORKSHOP_KIND;
  if (served && served.length) return { options: served, served: true };
  return { options: WORKSHOP_KIND_FLOOR, served: false };
}

export function adoptStageRegistry(registry: DwRegistry): DwRegistry {
  if (cachedRegistry && cachedRegistry.version === registry.version) return cachedRegistry;
  cachedRegistry = registry;
  return registry;
}

export function listReportTemplates() {
  return apiFetch<DwTemplate[]>("/design-workshops/templates");
}

export type DwListParams = {
  page?: number;
  pageSize?: number;
  search?: string | null;
  statusFilter?: string | null;
  workshopKind?: string | null;
  craftName?: string | null;
  state?: string | null;
  mineOnly?: boolean;
  includeDeleted?: boolean;
  deletedOnly?: boolean;
};

export function listDesignWorkshops(params: DwListParams) {
  return apiFetch<PageResult<DwSummary>>(
    `/design-workshops${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search ?? undefined,
      statusFilter: params.statusFilter ?? undefined,
      workshopKind: params.workshopKind ?? undefined,
      craftName: params.craftName ?? undefined,
      state: params.state ?? undefined,
      mineOnly: params.mineOnly ? "true" : undefined,
      includeDeleted: params.includeDeleted ? "true" : undefined,
      deletedOnly: params.deletedOnly ? "true" : undefined
    })}`
  );
}

export function createDesignWorkshop(body: DwCreateBody) {
  const { designerUserId, designerUserIds, ...rest } = body;
  return apiFetch<DwSummary>("/design-workshops", {
    method: "POST",
    body: JSON.stringify({
      ...rest,
      ...designerCreateFields({ chosen: designerUserIds ?? [], lead: designerUserId ?? "" })
    })
  });
}

export function getDesignWorkshop(id: string) {
  return apiFetch<DwDetail>(`/design-workshops/${id}`);
}

export function patchDesignWorkshop(id: string, body: DwUpdateBody) {
  return apiFetch<DwSummary>(`/design-workshops/${id}`, { method: "PATCH", body: JSON.stringify(body) });
}

export type DwFeedbackBody = {
  note: string;
  stageKey?: string | null;
  fieldKey?: string | null;
  recordedAt?: string | null;
};

export type DwFeedbackAnswer = DwSummary &
  DwInspectionFeedbackKeys & {
    readOnly?: boolean;
  };

export function recordInspectionFeedback(workshopId: string, body: DwFeedbackBody) {
  return apiFetch<DwFeedbackAnswer>(`/design-workshop-inspections/${workshopId}/feedback`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function sendWorkshopBackForRevision(workshopId: string, body: DwFeedbackBody) {
  return apiFetch<DwFeedbackAnswer>(`/design-workshop-inspections/${workshopId}/send-back`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function deleteDesignWorkshop(id: string) {
  return apiFetch<void>(`/design-workshops/${id}`, { method: "DELETE" });
}

export function restoreDesignWorkshop(id: string) {
  return apiFetch<DwSummary>(`/design-workshops/${id}/restore`, { method: "POST" });
}

export function listDesignWorkshopStages(id: string) {
  return apiFetch<DwStagesPayload>(`/design-workshops/${id}/stages`);
}

export function getDesignWorkshopStage(id: string, stageKey: string) {
  return apiFetch<DwStageData>(`/design-workshops/${id}/stages/${stageKey}`);
}

export function getDesignWorkshopMarketAnalysis(id: string) {
  return apiFetch<MarketFindingsPayload>(`/design-workshops/${id}/market-analysis`);
}

export function dwCostIntegrity(id: string) {
  return apiFetch<CostFindingsPayload>(`/design-workshops/${id}/cost-integrity`);
}

export function saveDesignWorkshopStage(id: string, stageKey: string, body: DwSaveBody) {
  return apiFetch<DwSaveResult>(`/design-workshops/${id}/stages/${stageKey}`, {
    method: "PUT",
    body: JSON.stringify({ replaceCollections: false, submit: false, ...body })
  });
}

export type DwReferenceOption = {
  id: string;
  label: string;
  sublabel: string;
  data: Record<string, unknown>;
  tentative?: boolean;
};

export type DwReferencePayload = {
  model: string;
  scope: string;
  scopedToWorkshop: boolean;
  filtered: boolean;
  truncated: boolean;
  outOfScope?: boolean;
  outOfScopeOption?: DwReferenceOption | null;
  tentativeFirst?: boolean;
  tentativeLabel?: string;
  options: DwReferenceOption[];
};

export type DwReferenceQuery = {
  model: string;
  scope?: string | null;
  filterBy?: string | null;
  search?: string | null;
  recordId?: string | null;
  limit?: number;
};

export function listStageReferences(workshopId: string, query: DwReferenceQuery) {
  return apiFetch<DwReferencePayload>(
    `/design-workshops/${workshopId}/references${buildQuery({
      model: query.model,
      scope: query.scope ?? undefined,
      filterBy: query.filterBy ?? undefined,
      search: query.search ?? undefined,
      recordId: query.recordId ?? undefined,
      limit: query.limit
    })}`
  );
}

const DW_REFERENCE_HYDRATION: Record<string, Record<string, string>> = {
  "workshopSetup.craftRef": {
    craftName: "craftName",
    craftLocalName: "craftLocalName",
    craftCategory: "craftCategory",
    craftPlace: "craftPlace",
    craftDescription: "documentedCraftNotes",
    craftDocumentedOn: "craftDocumentedOn",
    craftDocumentedAtWorkshop: "craftDocumentedAtWorkshop",
    craftMediaNote: "craftMediaNote",
    craftPhoto: "craftPhoto",
    craftPhotoCaption: "craftPhotoCaption"
  },
  "participant.artisanRef": {
    name: "name",
    localName: "localName",
    specialisation: "specialisation",
    craftStartDate: "craftStartDate",
    experienceYears: "experienceYears",
    gender: "gender",
    phone: "phone",
    village: "village",
    photo: "photo",
    email: "email",
    address: "address",
    notes: "recordNotes",
    dos: "dos",
    donts: "donts",
    pehchanCardAvailable: "pehchanCardAvailable",
    pehchanCardNumber: "artisanCardNo",
    aadhaarNumber: "aadhaarNumber",
    documentedOn: "documentedOn",
    documentedAtWorkshop: "documentedAtWorkshop",
    recordMediaNote: "recordMediaNote",
    age: "age",
    state: "state",
    district: "district",
    pincode: "pincode",
    subjectLocation: "subjectLocation",
    photoCaption: "photoCaption"
  },
  "tool.toolRef": {
    name: "name",
    localName: "localName",
    material: "material",
    usedFor: "usedFor",
    cost: "cost",
    photo: "photo",
    englishName: "englishName",
    yearsInUse: "yearsInUse",
    maker: "maker",
    traditionType: "traditionType",
    craftName: "craftName",
    place: "place",
    artisanName: "artisanName",
    improvements: "improvements",
    remarks: "remarks",
    lengthCm: "lengthCm",
    breadthCm: "breadthCm",
    heightCm: "heightCm",
    heightAsRecorded: "heightAsRecorded",
    widthAsRecorded: "widthAsRecorded",
    thicknessAsRecorded: "thicknessAsRecorded",
    weightAsRecorded: "weightAsRecorded",
    radiusAsRecorded: "radiusAsRecorded",
    recordState: "recordState",
    recordDistrict: "recordDistrict",
    recordVillage: "recordVillage",
    recordPincode: "recordPincode",
    subjectLocation: "recordSubjectLocation",
    recordMediaNote: "recordMediaNote",
    measurementMethodNote: "measurementMethodNote",
    usedByArtisans: "usedByArtisans",
    documentedOn: "documentedOn",
    photoCaption: "photoCaption"
  },
  "processStep.processRef": { name: "name", notes: "description", productName: "documentedFor" },
  "traditionalProcess.processRef": {
    recordMediaNote: "recordMediaNote",
    name: "documentedProcessName",
    notes: "documentedProcessNotes",
    productName: "documentedFor",
    steps: "documentedSteps",
    preProcessAvailable: "preProcessAvailable",
    documentedOn: "documentedOn"
  },
  "traditionalProcess.productRef": { name: "documentedFor" },
  "processStep.productRef": { name: "documentedFor" },
  "existingProduct.artisanRef": { name: "artisanName" },
  "existingProduct.productRef": {
    name: "name",
    category: "category",
    material: "material",
    price: "price",
    use: "use",
    recordState: "recordState",
    recordDistrict: "recordDistrict",
    recordVillage: "recordVillage",
    recordPincode: "recordPincode",
    subjectLocation: "recordSubjectLocation",
    recordMediaNote: "recordMediaNote",
    measurementMethodNote: "measurementMethodNote",
    photo: "productPhotos",
    localName: "localName",
    recordType: "recordType",
    craftName: "craftName",
    place: "place",
    artisanName: "artisanName",
    mainToolsUsed: "mainToolsUsed",
    costOfMaking: "costOfMaking",
    marketDemand: "marketDemand",
    lengthCm: "lengthCm",
    widthCm: "widthCm",
    heightCm: "heightCm",
    dimensionsNote: "dimensionsNote",
    productionTimeNote: "productionTimeNote",
    remarks: "remarks",
    documentedOn: "documentedOn",
    photoCaption: "productPhotosCaption"
  },
  "prototype.productRef": { name: "productName" },
  "artisanBaseline.interviewRef": {
    interviewTitle: "interviewTitle",
    interviewDate: "interviewDate",
    interviewPlace: "interviewPlace",
    interviewLanguage: "interviewLanguage",
    interviewArtisanCount: "interviewArtisanCount",
    interviewSectionsCovered: "interviewSectionsCovered",
    interviewQuestionsAnswered: "interviewQuestionsAnswered",
    interviewLastAnsweredOn: "interviewLastAnsweredOn",
    interviewMediaNote: "interviewMediaNote",
    interviewDocumentedOn: "interviewDocumentedOn",
    interviewDocumentedAtWorkshop: "interviewDocumentedAtWorkshop"
  },
  "surveyPlan.questionnaireRef": {
    name: "questionnaireName"
  },
  "prototypeValidation.prototypeRef": {
    lengthCm: "finalLengthCm",
    widthCm: "finalWidthCm",
    heightCm: "finalHeightCm"
  },
  "finalProduct.prototypeRef": {
    name: "name",
    materials: "materials",
    lengthCm: "lengthCm",
    widthCm: "widthCm",
    heightCm: "heightCm",
    weightG: "weightG",
    dimensionsNote: "dimensionsNote",
    makingTimeDays: "makingTimeDays",
    processSummary: "makingProcess"
  }
};

export function isMultiField(field: DwField): boolean {
  return field.type === "MULTI_ENUM" || field.type === "TAGS" || field.type === "IMAGE_LIST";
}

export function referenceHydrationFor(entity: DwEntity, refField: DwField): Record<string, string> {
  return DW_REFERENCE_HYDRATION[`${entity.key}.${refField.key}`] ?? {};
}

export function referenceHydrationPoints(): string[] {
  return Object.keys(DW_REFERENCE_HYDRATION);
}

export function stringifyRefValue(raw: unknown): string | null {
  if (typeof raw === "string") return raw.trim() ? raw : null;
  if (typeof raw === "number") return String(raw);
  return null;
}

export function hydrateFromReference(
  entity: DwEntity,
  refField: DwField,
  option: DwReferenceOption,
  row: DwEntryData,
  previousRefId: string
): Record<string, DwValue> {
  const mapping = DW_REFERENCE_HYDRATION[`${entity.key}.${refField.key}`];
  if (!mapping) return {};
  const replaced = Boolean(previousRefId) && previousRefId !== option.id;
  const patch: Record<string, DwValue> = {};

  if (replaced) {
    for (const targetKey of Object.values(mapping)) {
      const target = entity.fields.find((candidate) => candidate.key === targetKey);
      if (!target || isMultiField(target)) continue;
      patch[targetKey] = null;
    }
  }

  for (const [sourceKey, targetKey] of Object.entries(mapping)) {
    const raw = option.data?.[sourceKey];
    if (raw === null || raw === undefined || raw === "") continue;
    const target = entity.fields.find((candidate) => candidate.key === targetKey);
    if (!target || target.deprecated) continue;
    const multi = isMultiField(target);
    if (isFilled(row[targetKey]) && (!replaced || multi)) continue;
    if (target.type === "GEO") {
      const point = geoValue(raw as DwValue);
      if (point) patch[targetKey] = point;
      continue;
    }
    if (multi && Array.isArray(raw)) {
      if (raw.some((item) => typeof item !== "string" && typeof item !== "number")) continue;
      const items = raw.map((item) => pyStrip(String(item))).filter((item) => item !== "");
      if (items.length) patch[targetKey] = items;
      continue;
    }
    if (typeof raw !== "string" && typeof raw !== "number") continue;
    patch[targetKey] = multi ? [String(raw)] : raw;
  }
  return patch;
}

export function referenceDisplayHint(entity: DwEntity, refField: DwField, row: DwEntryData): string {
  const mapping = DW_REFERENCE_HYDRATION[`${entity.key}.${refField.key}`];
  if (!mapping) return "";
  for (const sourceKey of ["name", "craftName", "interviewTitle"]) {
    const targetKey = mapping[sourceKey];
    if (!targetKey) continue;
    const text = inputValue(row[targetKey]).trim();
    if (text) return text;
  }
  return "";
}

const routeProbes = new Map<string, Promise<boolean>>();

export function serverOffersRoute(path: string): Promise<boolean> {
  const cached = routeProbes.get(path);
  if (cached) return cached;
  const probe = apiFetch<unknown>(path)
    .then(() => true)
    .catch((error) => {
      if (error instanceof ApiError) {
        if (error.status === 404) return false;
        return true;
      }
      routeProbes.delete(path);
      return false;
    });
  routeProbes.set(path, probe);
  return probe;
}

export const DW_DICTATE_PATH = "/design-workshops/dictate";

export function dwDictatePathFor(workshopId: string): string {
  return `/design-workshops/${encodeURIComponent(workshopId)}/dictate`;
}
export const DW_OCR_IDENTITY_PATH = "/design-workshops/ocr/identity";

export type DwDictationAllowance = {
  dictationsLimit: number | null;
  dictationsUsed: number;
  dictationsRemaining: number | null;
  dictationDay: string;
};

export const DICTATION_ALLOWANCE_TTL_MS = 10 * 60 * 1000;

let dictationAllowanceInFlight: Promise<DwDictationAllowance | null> | null = null;
let dictationAllowanceHeld: { at: number; answer: DwDictationAllowance } | null = null;

export function dwDictationAllowance(now: number = Date.now()): Promise<DwDictationAllowance | null> {
  if (dictationAllowanceHeld && now - dictationAllowanceHeld.at < DICTATION_ALLOWANCE_TTL_MS) return Promise.resolve(dictationAllowanceHeld.answer);
  if (dictationAllowanceInFlight) return dictationAllowanceInFlight;
  const asking: Promise<DwDictationAllowance | null> = apiFetch<DwDictationAllowance>(
    "/design-workshops/dictation-allowance"
  )
    .then((answer) => {
      dictationAllowanceHeld = { at: now, answer };
      return answer;
    })
    .catch(() => null)
    .finally(() => {
      if (dictationAllowanceInFlight === asking) dictationAllowanceInFlight = null;
    });
  dictationAllowanceInFlight = asking;
  return asking;
}

export function rememberDictationAllowance(answer: DwDictationAllowance, now: number = Date.now()): void {
  dictationAllowanceHeld = { at: now, answer };
}

export function forgetDictationAllowanceInFlight(): void {
  dictationAllowanceInFlight = null;
  dictationAllowanceHeld = null;
}

export type DwConsentDecision = "GRANTED" | "REFUSED";

export type DwConsentDecisionRecord = {
  id: string;
  designWorkshopId: string;
  decision: DwConsentDecision | string;
  note: string | null;
  actorId: string | null;
  recordedAt: string | null;
  createdAt: string | null;
};

export type DwConsentResult = {
  workshop: DwSummary & { dictationConsentByName?: string | null };
  decisions: DwConsentDecisionRecord[];
};

export function recordDesignWorkshopDictationConsent(
  id: string,
  decision: DwConsentDecision,
  options: { note?: string | null; recordedAt?: string | null } = {}
): Promise<DwConsentResult> {
  return apiFetch<DwConsentResult>(`/design-workshops/${id}/dictation-consent`, {
    method: "POST",
    body: JSON.stringify({
      decision,
      note: (options.note ?? "").trim() || null,
      ...(options.recordedAt ? { recordedAt: options.recordedAt } : {})
    })
  });
}

export function dictationConsentSentence(consent: string | null | undefined): string {
  const token = (consent ?? "").trim().toUpperCase();
  if (token === "GRANTED") {
    return (
      "Material may be sent for transcription and AI tools."
    );
  }
  if (token === "REFUSED") {
    return (
      "Nothing is sent out. Dictation and AI tools are off."
    );
  }
  return (
    "On by default: material may be sent for transcription and AI tools until someone switches it off."
  );
}

export const DW_CONSENT_QUESTION =
  "On by default: recordings and dictation may be sent to an online transcription service. Dictated audio is " +
  "not kept — only the words. Attached recordings stay with the workshop. Tell the artisan, and switch this " +
  "off if they say no.";

export const DW_CONSENT_SWITCH_LABEL = "Recordings may be transcribed";

export const DW_CONSENT_ROW_TITLE = "Sending recordings out to be written down";

export type DwDictationResult = {
  status?: string | null;
  text?: string | null;
  mediaId?: string | null;
  provider?: string | null;
  languageHint?: string | null;
  message?: string | null;
  dictationsLimit?: number | null;
  dictationsUsed?: number | null;
  dictationsRemaining?: number | null;
  dictationDay?: string | null;
};

export function dictationAnswerSentence(result: DwDictationResult): string {
  const status = (result.status ?? "").toUpperCase();
  const message = (result.message ?? "").trim();
  const promisesARetry = /will retry automatically/i.test(message);
  if (status === "RATE_LIMITED" || promisesARetry) {
    return "Transcription is busy. Try again shortly, or type the answer.";
  }
  if (status === "FAILED") {
    return "Couldn't transcribe that. Try again, or type the answer.";
  }
  return "No words heard. Speak closer to the microphone, or type the answer.";
}

export function dictateAudio(
  blob: Blob,
  language: string,
  workshopId: string,
  extraFields: Record<string, string> = {}
): Promise<DwDictationResult> {
  const form = new FormData();
  form.append("file", blob, `dictation.${blob.type.includes("mp4") ? "m4a" : "webm"}`);
  form.append("languageHint", language);
  for (const [key, value] of Object.entries(extraFields)) form.append(key, value);
  return apiFetch<DwDictationResult>(dwDictatePathFor(workshopId), { method: "POST", body: form });
}

export type DwIdentityCandidate = {
  value?: string | null;
  kind?: string | null;
  confidence?: number | null;
  masked?: string | null;
};

export type DwIdentityOcrResult = {
  aadhaarCandidates?: DwIdentityCandidate[] | null;
  pehchanCandidates?: DwIdentityCandidate[] | null;
  rejectedAadhaarCount?: number | null;
  provider?: string | null;
  requiresConfirmation?: boolean | null;
  photograph?: {
    stored?: boolean | null;
    retention?: string | null;
    decisionRoute?: string | null;
  } | null;
};

export function photographWasNotStored(result: DwIdentityOcrResult): boolean {
  return result.photograph?.stored === false;
}

export async function readIdentityCard(
  file: File,
  retention: DwPhotoRetention = DW_PHOTO_RETENTION_DEFAULT
): Promise<DwIdentityOcrResult> {
  const form = new FormData();
  form.append("file", await prepareIdentityPhotograph(file));
  form.append("retention", retention);
  return apiFetch<DwIdentityOcrResult>(DW_OCR_IDENTITY_PATH, { method: "POST", body: form });
}

export type DwIdentityChoice = { value: string; kind: "AADHAAR" | "PEHCHAN"; confidence: number | null };

export function normalizePehchan(value: string | null | undefined): string {
  return (value ?? "").replace(/[^A-Za-z0-9]/g, "").toUpperCase();
}

export function identityChoices(
  result: DwIdentityOcrResult,
  kind: "AADHAAR" | "PEHCHAN" | "ANY",
  aadhaarProblem: (digits: string) => string | null
): DwIdentityChoice[] {
  const out: DwIdentityChoice[] = [];
  const push = (choice: DwIdentityChoice) => {
    if (!out.some((existing) => existing.value === choice.value)) out.push(choice);
  };
  if (kind === "AADHAAR" || kind === "ANY") {
    for (const candidate of result.aadhaarCandidates ?? []) {
      const digits = (candidate?.value ?? "").replace(/\D/g, "");
      if (!digits || aadhaarProblem(digits)) continue;
      push({ value: digits, kind: "AADHAAR", confidence: typeof candidate?.confidence === "number" ? candidate.confidence : null });
    }
  }
  if (kind === "PEHCHAN" || kind === "ANY") {
    for (const candidate of result.pehchanCandidates ?? []) {
      const cleaned = normalizePehchan(candidate?.value);
      if (cleaned.length < 4 || cleaned.length > 32) continue;
      push({ value: cleaned, kind: "PEHCHAN", confidence: typeof candidate?.confidence === "number" ? candidate.confidence : null });
    }
  }
  return out;
}

export function fetchReportSources(id: string) {
  return apiFetch<import("@/lib/offlineReport/assemble").ReportSources>(`/design-workshops/${id}/report/sources`);
}

export function previewDesignWorkshopReport(id: string, templateId?: string | null) {
  return apiFetch<DwPreview>(`/design-workshops/${id}/report/preview${buildQuery({ templateId: templateId ?? undefined })}`);
}

export function listDesignWorkshopExports(id: string) {
  return apiFetch<DwExport[]>(`/design-workshops/${id}/exports`);
}

export function fetchDesignWorkshopReportHistory(id: string) {
  return apiFetch<DwReportHistory>(`/design-workshops/${id}/report-history`);
}

export function recordDesignWorkshopExport(id: string, body: DwExportRecordBody) {
  return apiFetch<{ id: string }>(`/design-workshops/${id}/exports`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export type DwReportFile = {
  blob: Blob;
  fileName: string;
  reusedFrom?: string | null;
  exportId?: string | null;
  warnings: string[];
  warningCount: number;
};

const REPORT_FALLBACK_NAME: Record<string, string> = {
  DOCX: exportFilename("Workshop", "Report", "docx", { dated: false }),
  PDF: exportFilename("Workshop", "Report", "pdf", { dated: false })
};

export async function downloadDesignWorkshopReport(
  id: string,
  body: DwReportBody,
  options?: { via?: "approvals" }
): Promise<DwReportFile> {
  assertApiConfigured();

  const headers = new Headers({ "Content-Type": "application/json" });

  const prefix = options?.via === "approvals" ? "design-workshop-approvals" : "design-workshops";
  const response = await fetch(`${API_BASE}/api/${prefix}/${id}/report`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
    cache: "no-store"
  });

  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json") ? await response.json() : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload ? (payload as { detail: unknown }).detail : undefined;
    throw new ApiError(
      response.status,
      describeApiDetail(detail, response.statusText || "Something went wrong. Please try again."),
      payload,
      response.headers
    );
  }

  const raw = response.headers.get("x-report-warnings") ?? "";
  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? REPORT_FALLBACK_NAME[body.formats[0]] ?? "report",
    warnings: raw
      .split(";")
      .map((line) => line.trim())
      .filter(Boolean),
    warningCount: Number.parseInt(response.headers.get("x-report-warning-count") ?? "0", 10) || 0,
    reusedFrom: response.headers.get("x-report-reused-from") || null,
    exportId: response.headers.get("x-report-export-id") || null
  };
}

export { fileNameFromDisposition, saveBlobToDisk } from "@/lib/fileDownload";

import { fileNameFromDisposition } from "@/lib/fileDownload";

export function readNumber(value: DwValue | undefined): number | null {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "boolean") return null;
  if (Array.isArray(value) || typeof value === "object") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function inputValue(value: DwValue | undefined): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (Array.isArray(value) || typeof value === "object") return "";
  return String(value);
}

export function listValue(value: DwValue | undefined): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === "string");
  if (typeof value === "string" && value) return [value];
  return [];
}

export function geoValue(value: DwValue | undefined): DwGeoValue | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const candidate = value as Partial<DwGeoValue>;
  if (!Number.isFinite(candidate.lat) || !Number.isFinite(candidate.lon)) return null;
  return {
    lat: candidate.lat as number,
    lon: candidate.lon as number,
    ...(Number.isFinite(candidate.accuracy) ? { accuracy: candidate.accuracy as number } : {})
  };
}

export function isFilled(value: DwValue | undefined): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === "string") return value.trim().length > 0;
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === "object") {
    if ("blocks" in value) return !isStoredRichTextEmpty(value);
    return Object.keys(value).length > 0;
  }
  return true;
}

export const DW_KNOWN_WIDGETS: ReadonlySet<string> = new Set(["ATTENDANCE"]);

export function formFields(entity: DwEntity): DwField[] {
  return entity.fields.filter(
    (field) => !field.deprecated && !field.captionFor && !(field.widget && DW_KNOWN_WIDGETS.has(field.widget))
  );
}

export function captionFieldFor(entity: DwEntity, mediaKey: string): DwField | null {
  return entity.fields.find((field) => !field.deprecated && field.captionFor === mediaKey) ?? null;
}

export function splitByTier(fields: DwField[]): { primary: DwField[]; advanced: DwField[] } {
  return {
    primary: fields.filter((field) => field.tier !== "ADVANCED"),
    advanced: fields.filter((field) => field.tier === "ADVANCED")
  };
}

export function rowTitle(entity: DwEntity, row: DwRow, index: number): string {
  const labelled = entity.labelField ? inputValue(row[entity.labelField]) : "";
  if (labelled.trim()) return labelled.trim();
  for (const field of entity.fields) {
    if (field.deprecated) continue;
    if (field.type === "RICH_TEXT") {
      const summary = richSummary(row[field.key], 80);
      if (summary) return summary;
      continue;
    }
    if (field.type !== "TEXT" && field.type !== "LONG_TEXT") continue;
    const text = inputValue(row[field.key]).trim();
    if (text) return text;
  }
  return `${entity.title} ${index + 1}`;
}

export function entryDataOf(row: DwRow): DwEntryData {
  const out: DwEntryData = {};
  for (const [key, value] of Object.entries(row)) {
    if (key === "_entryId" || key === "_ordinal") continue;
    out[key] = value as DwValue;
  }
  return out;
}

export function blankRow(): DwRow {
  return { _clientKey: newClientKey() };
}

export function newClientKey(): string {
  const cryptoApi = typeof crypto !== "undefined" ? crypto : undefined;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") return cryptoApi.randomUUID();
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function overallPercent(completeness: Record<string, DwStageCompleteness> | undefined): number {
  const scores = Object.values(completeness ?? {});
  if (!scores.length) return 0;
  const required = scores.reduce((sum, score) => sum + score.requiredTotal, 0);
  const filled = scores.reduce((sum, score) => sum + score.requiredFilled, 0);
  if (required === 0) return 100;
  return Math.round((100 * filled) / required);
}

export type StageProgress = {
  stagesComplete: number;
  stagesTotal: number;
  percent: number;
  requiredLeft: number;
};

export function stageProgressPercent(stagesComplete: number, stagesTotal: number): number {
  if (stagesTotal <= 0) return 0;
  return Math.round((100 * Math.min(stagesComplete, stagesTotal)) / stagesTotal);
}

export function stageProgress(
  scores: readonly Pick<DwStageCompleteness, "isComplete" | "missing">[],
  stagesTotal: number = scores.length
): StageProgress {
  let stagesComplete = 0;
  let requiredLeft = 0;
  for (const score of scores) {
    if (score.isComplete) stagesComplete += 1;
    requiredLeft += score.missing.length;
  }
  return { stagesComplete, stagesTotal, percent: stageProgressPercent(stagesComplete, stagesTotal), requiredLeft };
}

export function runsText(runs: DwRun[] | undefined): string {
  return (runs ?? []).map((run) => run.text).join("");
}

export const DW_COPYABLE_STAGES: readonly string[] = [
  "INTRODUCTORY_ADMIN_DOCUMENTATION",
  "CLUSTER_CRAFT_BACKGROUND",
  "TRADITIONAL_PROCESS_BASELINE",
  "SURVEY_PLANNING"
];

export const DW_COPIED_SOURCE = "copied";

export type DwCopySource = {
  workshopId: string;
  title: string;
  craftName: string | null;
  clusterName: string | null;
  date: string | null;
  values: Record<string, DwValue>;
  designerName?: string | null;
};

export type DwCopySources = {
  fields: { key: string; label: string; type: string }[];
  own: DwCopySource[];
  reference: DwCopySource[];
};

export function fetchStageCopySources(id: string, stageKey: string) {
  return apiFetch<DwCopySources>(
    `/design-workshops/${encodeURIComponent(id)}/stages/${encodeURIComponent(stageKey)}/copy-sources`
  );
}

export function confirmCopiedFields(id: string, stageKey: string, fields: string[]) {
  return apiFetch<{ provenance: Record<string, DwFieldStamp>; confirmed: string[] }>(
    `/design-workshops/${encodeURIComponent(id)}/stages/${encodeURIComponent(stageKey)}/confirm-copied`,
    { method: "POST", body: JSON.stringify({ fields }) }
  );
}

export function unconfirmedCopies(provenance: Record<string, DwFieldStamp> | null | undefined): string[] {
  return Object.entries(provenance ?? {})
    .filter(([, stamp]) => stamp?.source === DW_COPIED_SOURCE)
    .map(([key]) => key);
}

export function pendingCopyKeys(
  copiedFrom: Record<string, string>,
  stamps: Record<string, DwFieldStamp> | null | undefined,
  editedKeys: ReadonlySet<string>
): Set<string> {
  const keys = new Set(Object.keys(copiedFrom));
  for (const key of unconfirmedCopies(stamps)) if (!editedKeys.has(key)) keys.add(key);
  return keys;
}

export function countedSingleton<T>(singleton: Record<string, T>, hidden: ReadonlySet<string>): Record<string, T> {
  if (!hidden.size) return singleton;
  return Object.fromEntries(Object.entries(singleton).filter(([key]) => !hidden.has(key)));
}

export function splitKeptCopies(
  keys: readonly string[],
  copiedFrom: Record<string, string>
): { local: string[]; remote: string[] } {
  return {
    local: keys.filter((key) => key in copiedFrom),
    remote: keys.filter((key) => !(key in copiedFrom))
  };
}
