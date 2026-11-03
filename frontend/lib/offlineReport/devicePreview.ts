"use client";

import { buildOfflineReport } from "@/lib/offlineReport/assemble";
import type { Registry } from "@/lib/offlineReport/builder";
import { draftReportInput } from "@/lib/offlineReport/generate";
import { getSources } from "@/lib/offlineReport/reportCache";
import { loadDraft, loadRegistry } from "@/lib/designWorkshopStore";
import type { DwPreview } from "@/lib/designWorkshops";

export async function buildPreviewOnDevice(draftId: string, userId: string | null, template = ""): Promise<DwPreview | null> {
  const draft = await loadDraft(draftId);
  if (!draft) return null;
  let registry: Registry;
  try {
    registry = (await loadRegistry()).registry as unknown as Registry;
  } catch {
    return null;
  }
  const sources = draft.remoteId ? ((await getSources(draft.remoteId, userId))?.sources ?? null) : null;
  const built = buildOfflineReport(registry, draftReportInput(draft, sources), {
    kind: "PREVIEW",
    options: { templateId: template || null }
  });
  const meta = built.document.meta;
  return {
    meta: {
      title: meta.title,
      subtitle: meta.subtitle,
      templateId: meta.template_id,
      templateName: meta.template_name,
      pageSize: meta.page_size,
      marginMm: meta.margin_mm
    } as DwPreview["meta"],
    blocks: built.document.blocks as unknown as DwPreview["blocks"],
    warnings: built.warnings
  };
}
