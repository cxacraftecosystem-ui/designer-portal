/**
 * Everything around the builder: what it is handed and in which order — `_report_inputs`,
 * `_build_only` (the preview) and `render_report` (the file) from
 * `backend/app/api/routes/design_workshops.py` and `backend/app/services/design_workshops.py`.
 *
 * THE INPUT IS ONE PLAIN OBJECT, `OfflineReportInput`, and it has exactly the shape of
 * `shared/report-parity/workshop.json`, so the parity spec feeds the fixture straight in and the
 * report screen feeds in what `draftReportInput` makes of the draft this browser already holds.
 */

import {
  DEFAULT_THEME,
  makeMeta,
  pyStrip,
  type ImageRef,
  type PageSize,
  type ReportDocument,
  type ReportMeta,
  type ReportTheme
} from "@/lib/offlineReport/model";
import {
  customSectionItems,
  type AiLayerItem,
  type CustomDefinitionInput,
  type QuestionnaireItem,
  type TranscriptItem
} from "@/lib/offlineReport/annexures";
import { buildReport, type ReferencedRecord, type Registry, type Row, type WorkshopData } from "@/lib/offlineReport/builder";
import { atlasDistrictPoints } from "@/lib/offlineReport/geocode";
import {
  applyReportSettings,
  inertSectionToggles,
  resolveTemplateId,
  templateById
} from "@/lib/offlineReport/templates";
import { ACCENT_PRESETS, normaliseHex, paletteFromAccent } from "@/lib/reportTheme";

/** What `GET /design-workshops/{id}/report/sources` answers, kept on the device. */
export type ReportSources = {
  workshopId: string;
  builtAt: string;
  references: Record<string, ReferencedRecord>;
  districtPoints: Record<string, [number, number]>;
  media: Record<string, ImageRef>;
  questionnaires: QuestionnaireItem[];
  transcripts: TranscriptItem[];
  aiLayers: AiLayerItem[];
  warnings: { questionnaires: string[]; transcripts: string[]; aiLayers: string[]; media: string[] };
};

export type OfflineWorkshop = {
  id: string;
  title: string;
  templateId: string;
  workshopCode: string | null;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  designerName: string | null;
  implementingAgency: string | null;
};

export type OfflineStage = { singleton: Row; collections: Record<string, Row[]>; custom?: Row };

export type OfflineReportInput = {
  generatedAt: string;
  workshop: OfflineWorkshop;
  stages: Record<string, OfflineStage>;
  customDefinition: CustomDefinitionInput;
  /** Null when this device has never had the sources for this workshop. */
  sources: ReportSources | null;
};

/** The per-file choices the report screen offers, as `ReportGenerateIn` carries them. */
export type ReportOptions = {
  templateId?: string | null;
  pageSize?: string | null;
  headerText?: string | null;
  footerText?: string | null;
  themeAccent?: string | null;
  fontPreset?: string | null;
  includePhotographs?: boolean | null;
  includeTranscripts?: boolean | null;
  includeAiLayers?: boolean | null;
};

export type ReportCase = { kind: "PREVIEW" | "FILE"; options?: ReportOptions; settings?: Row };

const FONT_PRESETS: Record<string, [string, string]> = {
  CALIBRI: ["Calibri Light", "Calibri"],
  CAMBRIA: ["Cambria", "Cambria"],
  GEORGIA: ["Georgia", "Georgia"],
  TIMES: ["Times New Roman", "Times New Roman"],
  ARIAL: ["Arial", "Arial"],
  VERDANA: ["Verdana", "Verdana"],
  GARAMOND: ["Garamond", "Garamond"]
};

/** `report_theme.resolve_font`. */
function resolveFont(requested: unknown, settings: Row): [string, string] | null {
  for (const candidate of [requested, settings.fontPreset]) {
    const token = candidate === null || candidate === undefined || candidate === "" ? "" : String(candidate).trim().toUpperCase();
    if (!token || token === "DEFAULT") continue;
    const preset = FONT_PRESETS[token];
    if (preset) return preset;
  }
  return null;
}

/** `report_theme.resolve_accent`. */
function resolveAccent(requested: unknown, settings: Row): string | null {
  const fromRequest = normaliseHex(requested);
  if (fromRequest) return fromRequest;
  const fromStage = normaliseHex(settings.themeAccent);
  if (fromStage) return fromStage;
  const preset = settings.themePreset;
  if (typeof preset === "string") {
    const found = ACCENT_PRESETS.find((p) => p.key === preset);
    if (found) return normaliseHex(found.hex);
  }
  return null;
}

/** `report_theme.theme_from_accent`, off the browser's port of the same derivation. */
function themeFromAccent(accent: string, base: ReportTheme): ReportTheme {
  const palette = paletteFromAccent(accent);
  const bare = (hex: string) => hex.replace(/^#/, "").toUpperCase();
  return {
    ...base,
    accent: bare(palette.accent),
    accent_soft: bare(palette.accentSoft),
    ink: bare(palette.ink),
    muted: bare(palette.muted),
    rule: bare(palette.rule),
    table_header_fill: bare(palette.tableHeadFill),
    table_header_text: bare(palette.tableHeadText),
    zebra_fill: bare(palette.zebra)
  };
}

/** `design_workshops.report_meta`. */
function reportMeta(workshop: OfflineWorkshop, templateId: string, settings: Row, generatedAt: string): ReportMeta {
  const template = templateById(templateId);
  const text = (key: string) => {
    const raw = settings[key];
    return raw === null || raw === undefined || raw === "" ? "" : pyStrip(String(raw));
  };
  const parts = [workshop.craftName, workshop.clusterName, workshop.state].filter((p): p is string => Boolean(p));
  const derivedSubtitle =
    parts.length > 1 ? [parts[0], parts.slice(1).join(", ")].join(" — ") : parts.length ? parts[0] : "";
  let pageSize: PageSize = template.pageSize;
  if (text("pageSize") === "A4" || text("pageSize") === "LETTER") pageSize = text("pageSize") as PageSize;
  return makeMeta({
    title: text("reportTitle") || workshop.title,
    subtitle: text("reportSubtitle") || derivedSubtitle,
    author: workshop.designerName || "",
    organisation: text("organisationLine") || workshop.implementingAgency || template.organisation,
    template_id: template.id,
    template_name: template.name,
    workshop_id: workshop.workshopCode || workshop.id,
    generated_at: generatedAt,
    page_size: pageSize,
    header_text: text("headerText") || [workshop.craftName, workshop.clusterName].filter(Boolean).join(" — "),
    footer_text: text("footerText") || `${template.name} · ${workshop.workshopCode || workshop.id}`
  });
}

/** `workshop_transcripts.wants_transcripts`. */
export function wantsTranscripts(option: boolean | null | undefined, settings: Row): boolean {
  if (option !== null && option !== undefined) return Boolean(option);
  return Boolean(settings.includeTranscripts);
}

export type BuiltReport = { document: ReportDocument; warnings: string[]; templateId: string };

/**
 * Build one report, as the server would: the preview the way `_build_only` builds it, a file the
 * way `render_report` does (the per-file choices apply only to a file — the preview endpoint takes a
 * template and nothing else).
 */
export function buildOfflineReport(registry: Registry, input: OfflineReportInput, kase: ReportCase): BuiltReport {
  const options = kase.options ?? {};
  const isFile = kase.kind === "FILE";
  const sources = input.sources;

  const singletons: Record<string, Row> = {};
  const collections: Record<string, Record<string, Row[]>> = {};
  for (const [stageKey, stage] of Object.entries(input.stages)) {
    if (stage.singleton && Object.keys(stage.singleton).length) singletons[stageKey] = { ...stage.singleton };
    for (const [entityKey, rows] of Object.entries(stage.collections ?? {})) {
      if (rows.length) (collections[stageKey] ??= {})[entityKey] = rows.map((r) => ({ ...r }));
    }
  }
  singletons.REPORT_GENERATION = { ...(singletons.REPORT_GENERATION ?? {}), ...(kase.settings ?? {}) };
  const settings = singletons.REPORT_GENERATION;

  const templateId = resolveTemplateId(options.templateId ?? null, settings, input.workshop.templateId);
  const base = templateById(templateId);
  const specials = new Set(base.sections.map((s) => s.special));

  const data: WorkshopData = {
    workshopId: input.workshop.id,
    title: input.workshop.title,
    singletons,
    collections,
    references: sources?.references ?? {},
    districtPoints: sources?.districtPoints ?? atlasDistrictPoints(),
    questionnaires: [],
    transcripts: [],
    aiLayers: [],
    customSections: []
  };

  const loadWarnings: string[] = [];
  if (specials.has("ANNEXURE_QUESTIONNAIRES") && sources) {
    data.questionnaires = sources.questionnaires;
    loadWarnings.push(...sources.warnings.questionnaires);
  }
  if (wantsTranscripts(isFile ? options.includeTranscripts : null, settings) && sources) {
    data.transcripts = sources.transcripts;
    loadWarnings.push(...sources.warnings.transcripts);
  }
  const includeAi = isFile ? Boolean(options.includeAiLayers) : false;
  if (includeAi && sources) {
    data.aiLayers = sources.aiLayers;
    loadWarnings.push(...sources.warnings.aiLayers);
  }
  const valuesByStage: Record<string, Row> = {};
  for (const [stageKey, stage] of Object.entries(input.stages)) if (stage.custom) valuesByStage[stageKey] = { ...stage.custom };
  const [items, customWarnings] = customSectionItems(input.customDefinition, valuesByStage);
  data.customSections = items;
  loadWarnings.push(...customWarnings);
  if (sources) loadWarnings.push(...sources.warnings.media);
  loadWarnings.push(...inertSectionToggles(base, settings));

  let meta = reportMeta(input.workshop, templateId, settings, input.generatedAt);
  if (isFile && (options.pageSize === "A4" || options.pageSize === "LETTER")) meta = { ...meta, page_size: options.pageSize };
  if (isFile && options.headerText) meta = { ...meta, header_text: options.headerText };
  if (isFile && options.footerText) meta = { ...meta, footer_text: options.footerText };
  const accent = resolveAccent(isFile ? options.themeAccent : null, settings);
  let theme: ReportTheme = accent ? themeFromAccent(accent, base.theme) : base.theme;
  const fonts = resolveFont(isFile ? options.fontPreset : null, settings);
  if (fonts) theme = { ...theme, heading_font: fonts[0], body_font: fonts[1] };
  const shaped = applyReportSettings(base, settings, {
    includePhotographs: isFile ? (options.includePhotographs ?? null) : null,
    includeAiLayers: isFile ? includeAi : null,
    customSections: items.map((i) => ({ key: i.key, title: i.title, stageKey: i.stageKey, sortOrder: i.sortOrder }))
  });

  const media = sources?.media ?? {};
  const resolveMedia = (id: string): ImageRef | null => media[id] ?? null;
  const { document, warnings } = buildReport(registry, data, templateId, shaped, resolveMedia, meta, theme);
  return { document, warnings: [...warnings, ...loadWarnings], templateId };
}

export { DEFAULT_THEME };
