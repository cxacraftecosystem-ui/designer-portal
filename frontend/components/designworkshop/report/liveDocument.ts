/**
 * Build the report document from what is on a stage form RIGHT NOW — the draft this browser holds,
 * with the open stage's unsaved answers laid over it — through the same builder the device uses for
 * an offline .docx (`lib/offlineReport/assemble.buildOfflineReport`, held block for block to the
 * server's by `offline-report-parity-unit.spec.ts`).
 *
 * Pure and synchronous apart from what it is handed, so the stage page can call it on a debounce
 * between keystrokes and a unit spec can call it in Node.
 */

import { buildOfflineReport, type ReportSources } from "@/lib/offlineReport/assemble";
import type { Registry, Row } from "@/lib/offlineReport/builder";
import { draftReportInput, type DraftLike } from "@/lib/offlineReport/generate";
import type { ImageRef, ReportDocument } from "@/lib/offlineReport/model";

/** What the stage form holds, unsaved. */
export type LiveStage = {
  singleton: Row;
  collections: Record<string, Row[]>;
  custom?: Row;
};

export type LiveDocument = {
  document: ReportDocument;
  warnings: string[];
};

/**
 * The draft, with `stageKey` replaced by the form's own values, built as a PREVIEW (the per-file
 * choices of the report screen do not apply, exactly as `GET /report/preview` ignores them).
 *
 * `extraMedia` are photographs the form references that the kept sources do not describe yet — a
 * picture attached since the sources were last fetched. Without them the builder would drop the
 * picture, which on a screen showing "what I just did" reads as the attachment having failed.
 */
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

/**
 * Every value on a stage form that names a stored photograph: the IMAGE and IMAGE_LIST fields of
 * the stage's entities, in the singleton and in every collection row.
 */
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
