import { ApiError, ApiUnconfiguredError, apiFetch, buildQuery } from "@/lib/api";
import { isUnreachable } from "@/lib/offline";

export type DwAiLayerKind =
  | "RAW_TRANSCRIPT"
  | "CLEANED_TRANSCRIPT"
  | "SUMMARY"
  | "OCR_TEXT"
  | "STRUCTURED_TEXT"
  | "TAGS"
  | "METADATA"
  | "PROOFREAD"
  | "EXPANDED"
  | "TRANSLATION"
  | "CAPTION"
  | "SUBTITLES";

export type DwAiTier = "TIER_1" | "TIER_2" | "TIER_3";

export type DwAiSourceKind = "MEDIA" | "LAYER" | "SUPPLIED_TEXT";

export type DwAiDecisionKind = "ACCEPTED" | "WITHDRAWN";

export const UNRECORDED = "UNRECORDED";

export const MAX_AI_LAYER_NOTE_CHARS = 500;

export type DwAiLayerSource = {
  kind: DwAiSourceKind | string;
  id: string | null;
  text: string | null;
};

export type DwAiLayer = {
  id: string;
  designWorkshopId: string;
  kind: DwAiLayerKind | string;
  tier: DwAiTier | string;
  source: DwAiLayerSource | null;
  provider: string;
  modelId: string;
  modelVersion: string | null;
  language: string | null;
  sourceLanguage: string | null;
  targetLanguage: string | null;
  producedAt: string | null;
  createdAt: string | null;
  createdById: string | null;
  accepted: boolean;
  acceptedAt: string | null;
  acceptedById: string | null;
  textChars: number | null;
  preview: string | null;
  payload: unknown;
  textWithheld: boolean;
  deletedAt: string | null;
  text?: string | null;
};

export type DwAiLayerList = {
  items: DwAiLayer[];
  total: number;
  accepted: number;
};

export type DwAiDecisionRecord = {
  id: string;
  layerId: string;
  decision: DwAiDecisionKind | "DECLINED" | string;
  note: string | null;
  actorId: string | null;
  actorName?: string | null;
  layerKind?: string | null;
  createdAt: string | null;
};

export type DwAiDecisionHistory = { items: DwAiDecisionRecord[]; total: number };

export type DwAiLayerRead = { layer: DwAiLayer; decisions: DwAiDecisionRecord[] };

export type DwAiDecisionResult = { layer: DwAiLayer; decisions: DwAiDecisionRecord[] };

export type DwAiLayerRegisterBody = {
  sourceMediaId: string;
  provider?: string;
  modelId?: string;
  modelVersion?: string;
  language?: string;
  producedAt?: string;
};

export type DwAiLayerRegisterResult = { items: DwAiLayer[]; total: number };

export type DwAiLayerListParams = {
  kind?: DwAiLayerKind | null;
  includeText?: boolean;
  includeDeleted?: boolean;
};

export function listDesignWorkshopAiLayers(id: string, params: DwAiLayerListParams = {}) {
  return apiFetch<DwAiLayerList>(
    `/design-workshops/${id}/ai-layers${buildQuery({
      kind: params.kind ?? undefined,
      includeText: params.includeText ? "true" : undefined,
      includeDeleted: params.includeDeleted ? "true" : undefined
    })}`
  );
}

export function getDesignWorkshopAiLayer(id: string, layerId: string) {
  return apiFetch<DwAiLayerRead>(`/design-workshops/${id}/ai-layers/${encodeURIComponent(layerId)}`);
}

export function listDesignWorkshopAiLayerDecisions(id: string) {
  return apiFetch<DwAiDecisionHistory>(`/design-workshops/${id}/ai-layers/decisions`);
}

export function registerDesignWorkshopAiLayer(id: string, body: DwAiLayerRegisterBody) {
  return apiFetch<DwAiLayerRegisterResult>(`/design-workshops/${id}/ai-layers`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export function acceptDesignWorkshopAiLayer(id: string, layerId: string, note?: string | null) {
  return apiFetch<DwAiDecisionResult>(`/design-workshops/${id}/ai-layers/${layerId}/accept`, {
    method: "POST",
    body: JSON.stringify({ note: (note ?? "").trim() || null })
  });
}

export function unacceptDesignWorkshopAiLayer(id: string, layerId: string, note?: string | null) {
  return apiFetch<DwAiDecisionResult>(`/design-workshops/${id}/ai-layers/${layerId}/unaccept`, {
    method: "POST",
    body: JSON.stringify({ note: (note ?? "").trim() || null })
  });
}

export function deleteDesignWorkshopAiLayer(id: string, layerId: string) {
  return apiFetch<void>(`/design-workshops/${id}/ai-layers/${layerId}`, { method: "DELETE" });
}

export function isUnrecorded(value: string | null | undefined): boolean {
  return (value ?? "").trim().toUpperCase() === UNRECORDED;
}

const KIND_LABELS: Record<DwAiLayerKind, string> = {
  RAW_TRANSCRIPT: "Machine transcript",
  CLEANED_TRANSCRIPT: "AI-cleaned transcript",
  SUMMARY: "AI summary",
  OCR_TEXT: "Text read off a photograph",
  STRUCTURED_TEXT: "Fields read off a photograph",
  TAGS: "Suggested tags",
  METADATA: "Extracted details",
  PROOFREAD: "AI-corrected spelling and punctuation",
  EXPANDED: "Prose written by AI from a designer's note",
  TRANSLATION: "AI translation",
  CAPTION: "AI description of a photograph or video",
  SUBTITLES: "AI subtitles, with their timings"
};

export function layerKindLabel(kind: string | null | undefined): string {
  const known = KIND_LABELS[(kind ?? "") as DwAiLayerKind];
  if (known) return known;
  return kind ? `An unfamiliar kind of layer (${kind})` : "A layer with no kind recorded";
}

export function layerKindNoun(kind: string | null | undefined): string {
  const known = KIND_LABELS[(kind ?? "") as DwAiLayerKind];
  return known ? known.toLowerCase() : "layer";
}

const KIND_NOTES: Record<DwAiLayerKind, string> = {
  RAW_TRANSCRIPT: "The transcript as the model produced it.",
  CLEANED_TRANSCRIPT:
    "Rewritten as readable dialogue, often in English.",
  SUMMARY: "The model's summary — not the artisan's words.",
  OCR_TEXT: "The text a model read off the photograph.",
  STRUCTURED_TEXT: "Fields picked out of the photo's text.",
  TAGS: "Suggested tags.",
  METADATA: "Details a model picked out of the text.",
  PROOFREAD:
    "Spelling, grammar and punctuation only. The original is kept.",
  EXPANDED:
    "A machine wrote these sentences from a short note the designer made. It is the only kind here " +
      "that invents: anything in it that is not in the note — a detail, a reason, a connection " +
      "between two things — was supplied by the model and was not recorded in the field. Treat the " +
      "note as the record and this as a reading of it, and check any specific claim against the " +
      "workshop's own material before quoting it. Nothing can be made from an expansion.",
  TRANSLATION:
    "Added beside the original, never replacing it.",
  CAPTION:
    "A one-line description. Check it against the picture.",
  SUBTITLES:
    "Timed captions. Speaker labels are a guess."
};

export function layerKindNote(kind: string | null | undefined): string | null {
  return KIND_NOTES[(kind ?? "") as DwAiLayerKind] ?? null;
}

const TIER_LABELS: Record<DwAiTier, string> = {
  TIER_1: "On the handset",
  TIER_2: "On the handset, small model",
  TIER_3: "In the cloud"
};

const TIER_SENTENCES: Record<DwAiTier, string> = {
  TIER_1:
    "Ran on the phone. Works offline; nothing left the device.",
  TIER_2:
    "A small model on the phone. Nothing left the device.",
  TIER_3:
    "Ran in the cloud, with the craft vocabulary."
};

export function tierLabel(tier: string | null | undefined): string {
  const known = TIER_LABELS[(tier ?? "") as DwAiTier];
  if (known) return known;
  return tier ? `An unfamiliar tier (${tier})` : "Tier not recorded";
}

export function tierSentence(tier: string | null | undefined): string {
  const known = TIER_SENTENCES[(tier ?? "") as DwAiTier];
  if (known) return known;
  return (
    "Where this model ran can't be described here. Reload the page to update the app."
  );
}

export function decisionLabel(decision: string | null | undefined): string {
  if (decision === "ACCEPTED") return "Accepted";
  if (decision === "WITHDRAWN") return "Acceptance withdrawn";
  if (decision === "DECLINED") return "Declined";
  return decision ? `An unfamiliar decision (${decision})` : "A decision with nothing recorded";
}

export type DwAiProvenance = {
  provider: string;
  model: string;
  language: string;
  unrecorded: boolean;
};

export function layerProvenance(layer: DwAiLayer): DwAiProvenance {
  const providerMissing = isUnrecorded(layer.provider) || !(layer.provider ?? "").trim();
  const modelMissing = isUnrecorded(layer.modelId) || !(layer.modelId ?? "").trim();
  const version = (layer.modelVersion ?? "").trim();
  const language = (layer.language ?? "").trim();
  return {
    provider: providerMissing ? "not recorded" : layer.provider.trim(),
    model: modelMissing ? "not recorded" : version ? `${layer.modelId.trim()} · ${version}` : layer.modelId.trim(),
    language: !language ? "not recorded" : language === "multi" ? "multi — mixed, code-switched speech" : language,
    unrecorded: providerMissing && modelMissing
  };
}

export type DwAiLanguagePair = { from: string; into: string };

export function layerLanguagePair(layer: DwAiLayer): DwAiLanguagePair | null {
  const source = (layer.sourceLanguage ?? "").trim();
  const target = (layer.targetLanguage ?? "").trim();
  if (!source && !target) return null;
  const say = (value: string) =>
    !value
      ? "not recorded"
      : isUnrecorded(value)
        ? "not recorded"
        : value === "multi"
          ? "multi — mixed, code-switched speech"
          : value;
  return { from: say(source), into: say(target) };
}

export type DwAiLayerNode = { layer: DwAiLayer; depth: number; children: DwAiLayerNode[] };

export type DwAiLayerGroup = { mediaId: string; roots: DwAiLayerNode[] };

export type DwAiUnrootedReason = "NO_SOURCE" | "SOURCE_NOT_LISTED" | "CIRCULAR" | "UNKNOWN_SOURCE";

export type DwAiUnrooted = { node: DwAiLayerNode; reason: DwAiUnrootedReason };

export type DwAiLayerGrouping = {
  recordings: DwAiLayerGroup[];
  notes: DwAiLayerNode[];
  unrooted: DwAiUnrooted[];
};

function byCreatedAtAsc(a: DwAiLayer, b: DwAiLayer): number {
  if (a.createdAt === b.createdAt) return a.id < b.id ? -1 : 1;
  if (!a.createdAt) return 1;
  if (!b.createdAt) return -1;
  return a.createdAt < b.createdAt ? -1 : 1;
}

function newestCreatedAt(node: DwAiLayerNode): string {
  const own = node.layer.createdAt ?? "";
  return node.children.reduce((latest, child) => {
    const childLatest = newestCreatedAt(child);
    return childLatest > latest ? childLatest : latest;
  }, own);
}

export function groupAiLayers(items: DwAiLayer[]): DwAiLayerGrouping {
  const byId = new Map(items.map((layer) => [layer.id, layer]));
  const childrenOf = new Map<string, DwAiLayer[]>();
  const mediaRooted: DwAiLayer[] = [];
  const textRooted: DwAiLayer[] = [];

  for (const layer of items) {
    const source = layer.source;
    if (source && source.kind === "LAYER" && source.id && byId.has(source.id)) {
      const siblings = childrenOf.get(source.id);
      if (siblings) siblings.push(layer);
      else childrenOf.set(source.id, [layer]);
    } else if (source && source.kind === "MEDIA") {
      mediaRooted.push(layer);
    } else if (source && source.kind === "SUPPLIED_TEXT") {
      textRooted.push(layer);
    }
  }
  for (const siblings of childrenOf.values()) siblings.sort(byCreatedAtAsc);

  const visited = new Set<string>();
  const build = (layer: DwAiLayer, depth: number): DwAiLayerNode => {
    visited.add(layer.id);
    const children: DwAiLayerNode[] = [];
    for (const child of childrenOf.get(layer.id) ?? []) {
      if (visited.has(child.id)) continue;
      children.push(build(child, depth + 1));
    }
    return { layer, depth, children };
  };

  const groups = new Map<string, DwAiLayerNode[]>();
  for (const layer of mediaRooted.slice().sort(byCreatedAtAsc)) {
    if (visited.has(layer.id)) continue;
    const mediaId = layer.source?.id ?? "";
    const roots = groups.get(mediaId);
    const node = build(layer, 0);
    if (roots) roots.push(node);
    else groups.set(mediaId, [node]);
  }

  const notes: DwAiLayerNode[] = [];
  for (const layer of textRooted.slice().sort(byCreatedAtAsc)) {
    if (visited.has(layer.id)) continue;
    notes.push(build(layer, 0));
  }
  notes.sort((a, b) => (newestCreatedAt(a) > newestCreatedAt(b) ? -1 : 1));

  const unrooted: DwAiUnrooted[] = [];
  for (const layer of items) {
    if (visited.has(layer.id)) continue;
    const source = layer.source;
    const reason: DwAiUnrootedReason = !source
      ? "NO_SOURCE"
      : source.kind !== "MEDIA" && source.kind !== "LAYER" && source.kind !== "SUPPLIED_TEXT"
        ? "UNKNOWN_SOURCE"
        : source.kind === "LAYER" && (!source.id || !byId.has(source.id))
          ? "SOURCE_NOT_LISTED"
          : "CIRCULAR";
    unrooted.push({ node: build(layer, 0), reason });
  }

  const recordings = [...groups.entries()]
    .map(([mediaId, roots]) => ({
      mediaId,
      roots: roots.slice().sort((a, b) => (newestCreatedAt(a) > newestCreatedAt(b) ? -1 : 1))
    }))
    .sort((a, b) => {
      const left = a.roots.length ? newestCreatedAt(a.roots[0]) : "";
      const right = b.roots.length ? newestCreatedAt(b.roots[0]) : "";
      if (left === right) return a.mediaId < b.mediaId ? -1 : 1;
      return left > right ? -1 : 1;
    });

  return { recordings, notes, unrooted };
}

const DERIVABLE_PROSE_KINDS: readonly DwAiLayerKind[] = [
  "RAW_TRANSCRIPT",
  "CLEANED_TRANSCRIPT",
  "SUMMARY",
  "OCR_TEXT",
  "PROOFREAD",
  "TRANSLATION",
  "CAPTION"
];

export function aiVerbPlacementAllows(kind: string | null | undefined, parentKind: string | null | undefined): boolean {
  const verb = (kind ?? "") as DwAiLayerKind;
  const parent = (parentKind ?? "") as DwAiLayerKind;
  if (!DERIVABLE_PROSE_KINDS.includes(parent)) return false;
  if (verb === "PROOFREAD") return parent !== "PROOFREAD";
  if (verb === "TRANSLATION") return parent !== "TRANSLATION";
  return false;
}

export function flattenAiLayerNodes(nodes: DwAiLayerNode[]): DwAiLayerNode[] {
  const out: DwAiLayerNode[] = [];
  const walk = (node: DwAiLayerNode) => {
    out.push(node);
    node.children.forEach(walk);
  };
  nodes.forEach(walk);
  return out;
}

export type DwAiPayloadView =
  | { shape: "TAGS"; tags: string[] }
  | { shape: "ROWS"; rows: { key: string; value: string }[] }
  | { shape: "JSON"; json: string; truncated: boolean }
  | null;

export const AI_PAYLOAD_JSON_CHARS = 4000;

export function readAiPayload(payload: unknown): DwAiPayloadView {
  if (payload === null || payload === undefined) return null;
  if (Array.isArray(payload) && payload.length && payload.every((entry) => typeof entry === "string")) {
    return { shape: "TAGS", tags: payload as string[] };
  }
  if (!Array.isArray(payload) && typeof payload === "object") {
    const entries = Object.entries(payload as Record<string, unknown>);
    const scalar = entries.every(
      ([, value]) => value === null || ["string", "number", "boolean"].includes(typeof value)
    );
    if (entries.length && scalar) {
      return {
        shape: "ROWS",
        rows: entries.map(([key, value]) => ({ key, value: value === null ? "not recorded" : String(value) }))
      };
    }
  }
  let json: string;
  try {
    json = JSON.stringify(payload, null, 2) ?? String(payload);
  } catch {
    return { shape: "JSON", json: "This content couldn't be shown.", truncated: false };
  }
  if (json.length <= AI_PAYLOAD_JSON_CHARS) return { shape: "JSON", json, truncated: false };
  return { shape: "JSON", json: json.slice(0, AI_PAYLOAD_JSON_CHARS), truncated: true };
}

const NO_CONNECTION =
  "AI layers can't be read or changed without a connection. Nothing was saved and nothing will be " +
  "sent later, so reconnect and press Reload to try again. The workshop's own stages are safe on " +
  "this device.";

function serverSaidSomething(error: ApiError): boolean {
  const body = error.payload;
  if (!body || typeof body !== "object") return false;
  const detail = (body as { detail?: unknown }).detail;
  if (typeof detail === "string") return detail.trim().length > 0;
  return detail !== undefined && detail !== null;
}

export function aiLayerProblem(error: unknown, fallback: string): string {
  if (error instanceof ApiUnconfiguredError) return error.message;
  if (error instanceof ApiError) {
    if (serverSaidSomething(error) && error.message.trim()) return error.message;
    if (isUnreachable(error)) return NO_CONNECTION;
    return (
      "Something went wrong, and it isn't clear whether your change was saved. Press Reload to check " +
        "before trying again."
    );
  }
  if (isUnreachable(error)) return NO_CONNECTION;
  return error instanceof Error && error.message.trim() ? error.message : fallback;
}

export function decisionActor(entry: DwAiDecisionRecord, userId: string | null): string {
  if (entry.actorId && userId && entry.actorId === userId) return "you";
  if (entry.actorName) return entry.actorName;
  return entry.actorId ? "another account" : "an account that no longer exists";
}
