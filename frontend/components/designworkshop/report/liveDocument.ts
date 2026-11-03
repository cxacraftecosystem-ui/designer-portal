import { buildOfflineReport, type ReportSources } from "@/lib/offlineReport/assemble";
import type { Registry, Row } from "@/lib/offlineReport/builder";
import { draftReportInput, type DraftLike } from "@/lib/offlineReport/generate";
import type { ImageRef, ReportDocument } from "@/lib/offlineReport/model";

export type LiveStage = {
  singleton: Row;
  collections: Record<string, Row[]>;
  custom?: Row;
};

export type LiveDocument = {
  document: ReportDocument;
  warnings: string[];
};

export function buildLiveDocument(
  registry: Registry,
  draft: DraftLike,
  stageKey: string,
  live: LiveStage,
  sources: ReportSources | null,
  extraMedia: Record<string, ImageRef> = {},
  now: Date = new Date()
): LiveDocument {
  const stages: DraftLike["stages"] = {
    ...draft.stages,
    [stageKey]: {
      singletons: { live: live.singleton ?? {} },
      collections: live.collections ?? {},
      ...(live.custom && Object.keys(live.custom).length ? { custom: live.custom } : {})
    }
  };
  const withMedia: ReportSources | null =
    Object.keys(extraMedia).length || sources
      ? {
          ...(sources ?? emptySources(draft.remoteId ?? draft.localId)),
          media: { ...extraMedia, ...(sources?.media ?? {}) }
        }
      : null;
  const built = buildOfflineReport(registry, draftReportInput({ ...draft, stages }, withMedia, now), {
    kind: "PREVIEW",
    options: { templateId: null }
  });
  return { document: built.document, warnings: built.warnings };
}

function emptySources(workshopId: string): ReportSources {
  return {
    workshopId,
    builtAt: "",
    references: {},
    districtPoints: {},
    media: {},
    questionnaires: [],
    transcripts: [],
    aiLayers: [],
    warnings: { questionnaires: [], transcripts: [], aiLayers: [], media: [] }
  };
}

export function stageMediaIds(
  stage: { entities: Array<{ key: string; fields: Array<{ key: string; type: string }> }> } | null | undefined,
  live: LiveStage
): string[] {
  if (!stage) return [];
  const found = new Set<string>();
  const take = (value: unknown) => {
    if (typeof value === "string" && value) found.add(value);
    else if (Array.isArray(value)) for (const item of value) if (typeof item === "string" && item) found.add(item);
  };
  for (const entity of stage.entities) {
    const imageKeys = entity.fields.filter((f) => f.type === "IMAGE" || f.type === "IMAGE_LIST").map((f) => f.key);
    if (!imageKeys.length) continue;
    const rows = [live.singleton ?? {}, ...(live.collections?.[entity.key] ?? [])];
    for (const row of rows) for (const key of imageKeys) take(row[key]);
  }
  return [...found].sort();
}
