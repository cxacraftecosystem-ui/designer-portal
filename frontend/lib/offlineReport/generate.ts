/**
 * The report screen's one door into building a report on the device: from the draft this browser
 * holds and the sources it has kept, to a preview or a .docx/.pdf, with the warnings that belong
 * beside it.
 *
 * Pure apart from the writers' injected loaders, so `offline-report-files-unit.spec.ts` drives it in
 * Node with the fixture workshop and real fonts.
 */

import { buildOfflineReport, type OfflineReportInput, type ReportOptions, type ReportSources } from "@/lib/offlineReport/assemble";
import type { Registry, Row } from "@/lib/offlineReport/builder";
import { renderDocx, type FigureRasteriser, type ImageLoader } from "@/lib/offlineReport/docx";
import type { ReportDocument, Script } from "@/lib/offlineReport/model";
import { renderPdf, type FontSource, type PdfMakeLike } from "@/lib/offlineReport/pdf";

export type DraftLike = {
  localId: string;
  remoteId: string | null;
  header: {
    title: string;
    templateId: string;
    workshopCode: string | null;
    craftName: string | null;
    clusterName: string | null;
    state: string | null;
    designerName: string | null;
  };
  stages: Record<
    string,
    { singletons: Record<string, Row>; collections: Record<string, Row[]>; custom?: Row }
  >;
  customDefinition?: OfflineReportInput["customDefinition"] | null;
};

/**
 * The draft as the builder reads a workshop: one singleton per stage (the server keeps exactly one),
 * every collection's rows in the draft's order, and the designer's own answers per stage.
 */
export function draftReportInput(draft: DraftLike, sources: ReportSources | null, now: Date = new Date()): OfflineReportInput {
  const stages: OfflineReportInput["stages"] = {};
  for (const [stageKey, stage] of Object.entries(draft.stages)) {
    const singleton: Row = {};
    for (const values of Object.values(stage.singletons ?? {})) Object.assign(singleton, values);
    stages[stageKey] = {
      singleton,
      collections: Object.fromEntries(Object.entries(stage.collections ?? {}).map(([k, rows]) => [k, rows.map((r) => ({ ...r }))])),
      ...(stage.custom && Object.keys(stage.custom).length ? { custom: { ...stage.custom } } : {})
    };
  }
  const setup = stages.WORKSHOP_SETUP?.singleton ?? {};
  const text = (value: unknown) => (typeof value === "string" && value.trim() ? value.trim() : null);
  return {
    generatedAt: now.toISOString().replace(/\.\d{3}Z$/, "Z"),
    workshop: {
      id: draft.remoteId ?? draft.localId,
      title: draft.header.title,
      templateId: draft.header.templateId,
      workshopCode: draft.header.workshopCode ?? text(setup.workshopCode),
      craftName: draft.header.craftName ?? text(setup.craftName),
      clusterName: draft.header.clusterName ?? text(setup.clusterName),
      state: draft.header.state ?? text(setup.state),
      designerName: draft.header.designerName ?? text(setup.designerName),
      implementingAgency: text(setup.implementingAgency)
    },
    stages,
    customDefinition: draft.customDefinition ?? null,
    sources
  };
}

/** `_report_file_name`: `DesignWorkshop_<code or title>_<yyyymmdd>.<ext>`. */
export function reportFileName(workshopCode: string | null, title: string, format: "DOCX" | "PDF", now: Date = new Date()): string {
  const stem = workshopCode || title || "workshop";
  const safe = Array.from(String(stem))
    .map((c) => (/[\p{L}\p{N}]/u.test(c) || " -_".includes(c) ? c : "_"))
    .join("")
    .trim();
  const joined = safe.split(/\s+/).filter(Boolean).join("_").slice(0, 60) || "workshop";
  const stamp = now.toISOString().slice(0, 10).replace(/-/g, "");
  return `DesignWorkshop_${joined}_${stamp}.${format === "PDF" ? "pdf" : "docx"}`;
}

const SCRIPT_NAMES: Record<string, string> = {
  DEVANAGARI: "Devanagari",
  BENGALI: "Bengali",
  ODIA: "Odia",
  GUJARATI: "Gujarati",
  TAMIL: "Tamil",
  TELUGU: "Telugu",
  KANNADA: "Kannada",
  MALAYALAM: "Malayalam",
  GURMUKHI: "Gurmukhi"
};

/** `_dropped_warnings`, worded for a file written on this device. */
export function droppedWarnings(dropped: readonly string[]): string[] {
  const said: string[] = [];
  if (dropped.some((d) => d.startsWith("map:"))) {
    said.push(
      "The locator map could not be drawn, so the section that places the workshop and its artisans is empty in this file. " +
        "Nothing is wrong with the workshop's data; open the report once with a connection and the map's borders are kept for next time."
    );
  }
  const figures = dropped.filter((d) => d.startsWith("chart:") || d.startsWith("figure:")).length;
  if (figures) said.push(`${figures} figure(s) could not be drawn. Their numbers are still in the tables.`);
  const photographs = dropped.filter((d) => !d.startsWith("map:") && !d.startsWith("chart:") && !d.startsWith("figure:")).length;
  if (photographs) {
    said.push(
      `${photographs} photograph(s) are not on this device and are not in this file. ` +
        "Open the workshop's report once with a connection and every photograph it shows is kept for the next time."
    );
  }
  return said;
}

export function missingScriptWarning(scripts: readonly Script[]): string[] {
  if (!scripts.length) return [];
  const names = scripts.map((s) => SCRIPT_NAMES[s] ?? s).join(", ");
  return [
    `This PDF prints empty boxes for text in these scripts: ${names}. ` +
      "Their typefaces are not on this device yet. The Word document in the same download is correct."
  ];
}

export type OfflineFile = { blob: Blob; fileName: string; warnings: string[]; document: ReportDocument };

export type OfflineWriters = {
  loadImage: ImageLoader;
  rasterise: FigureRasteriser;
  pdfmake: () => Promise<PdfMakeLike>;
  fontSource: FontSource;
};

/** Build and write one file on the device. */
export async function generateOfflineFile(
  registry: Registry,
  input: OfflineReportInput,
  format: "DOCX" | "PDF",
  options: ReportOptions,
  writers: OfflineWriters,
  now: Date = new Date()
): Promise<OfflineFile> {
  const built = buildOfflineReport(registry, input, { kind: "FILE", options });
  const fileName = reportFileName(input.workshop.workshopCode, input.workshop.title, format, now);
  if (format === "DOCX") {
    const { blob, dropped } = await renderDocx(built.document, writers.loadImage, writers.rasterise);
    return { blob, fileName, warnings: [...built.warnings, ...droppedWarnings(dropped)], document: built.document };
  }
  const pdf = await renderPdf(built.document, await writers.pdfmake(), writers.loadImage, writers.rasterise, writers.fontSource);
  if (pdf.fontsMissing) {
    throw new Error(
      "The typeface the PDF is set in is not on this device yet, so the PDF cannot be written. " +
        "The Word document can be — or open the report once with a connection and the PDF works from then on."
    );
  }
  return {
    blob: new Blob([pdf.bytes as BlobPart], { type: "application/pdf" }),
    fileName,
    warnings: [...built.warnings, ...droppedWarnings(pdf.dropped), ...missingScriptWarning(pdf.missingScripts)],
    document: built.document
  };
}
