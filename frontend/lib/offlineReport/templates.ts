/**
 * The six report templates and the stage-20 settings that shape them, ported from
 * `backend/app/services/report_templates.py` so the browser can build the same document offline.
 *
 * A TEMPLATE IS DATA, and this file is that data written down a second time. The template list the
 * report screen offers still comes from the server whenever there is a connection
 * (`GET /design-workshops/report-templates`); this copy is what a browser with none builds from, and
 * `offline-report-parity-unit.spec.ts` builds every template over the shared fixture and compares the
 * result with the server's, so a section added, moved or renamed there fails here.
 */

import { DEFAULT_THEME, type ReportTheme } from "@/lib/offlineReport/model";

export type Tier = "BASIC" | "STANDARD" | "ADVANCED";
export const TIER_RANK: Record<Tier, number> = { BASIC: 0, STANDARD: 1, ADVANCED: 2 };

export type Presentation = "KEY_VALUE" | "NARRATIVE" | "TABLE" | "CARDS" | "GALLERY" | "AUTO";

export type SpecialSection =
  | "COVER"
  | "TOC"
  | "ACKNOWLEDGEMENT"
  | "SUMMARY_METRICS"
  | "SIGNATURES"
  | "ANNEXURE_MEDIA"
  | "ANNEXURE_TRANSCRIPTS"
  | "ANNEXURE_QUESTIONNAIRES"
  | "ANNEXURE_AI_LAYERS"
  | "CUSTOM_SECTION"
  | "COMPLETENESS"
  | "MAP"
  | "CHART";

export type TemplateSection = {
  stageKey: string;
  special: SpecialSection | null;
  heading: string;
  presentation: Presentation;
  includePhotos: boolean;
  photoColumns: number;
  maxPhotos: number;
  pageBreakBefore: boolean;
  omitIfEmpty: boolean;
  intro: string;
  entities: string[];
  includeFigures: boolean;
  figures: string[];
  customKey: string;
};

export function section(partial: Partial<TemplateSection>): TemplateSection {
  return {
    stageKey: "",
    special: null,
    heading: "",
    presentation: "AUTO",
    includePhotos: true,
    photoColumns: 2,
    maxPhotos: 0,
    pageBreakBefore: false,
    omitIfEmpty: true,
    intro: "",
    entities: [],
    includeFigures: true,
    figures: [],
    customKey: "",
    ...partial
  };
}

export type ReportTemplate = {
  id: string;
  name: string;
  description: string;
  sections: TemplateSection[];
  maxTier: Tier;
  pageSize: "A4" | "LETTER";
  theme: ReportTheme;
  numberHeadings: boolean;
  organisation: string;
  showEmptyNote: boolean;
};

export function sectionFor(template: ReportTemplate, stageKey: string): TemplateSection | undefined {
  return template.sections.find((s) => s.stageKey === stageKey);
}

export const NARRATIVE_ORDER: readonly string[] = [
  "WORKSHOP_SETUP",
  "INTRODUCTORY_ADMIN_DOCUMENTATION",
  "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
  "CLUSTER_CRAFT_BACKGROUND",
  "TRADITIONAL_PROCESS_BASELINE",
  "EXISTING_PRODUCTS_BASELINE",
  "SURVEY_PLANNING",
  "MARKET_SURVEY_CAPTURE",
  "MARKET_ANALYSIS_DIRECTION",
  "DESIGN_BRIEF",
  "SKETCH_DEVELOPMENT",
  "SKETCH_REVIEW",
  "PROTOTYPE_DEVELOPMENT",
  "PROTOTYPE_ITERATION",
  "PROTOTYPE_VALIDATION",
  "FINAL_PROTOTYPE_DOCUMENTATION",
  "COSTING_MARKET_LINKAGE",
  "WORKSHOP_OUTCOMES",
  "INSPECTION_CLOSING",
  "POST_WORKSHOP_FOLLOWUP"
];

const TABULAR: Record<string, Presentation> = {
  WORKSHOP_PLAN_PARTICIPANTS_OPENING: "TABLE",
  SURVEY_PLANNING: "TABLE",
  MARKET_SURVEY_CAPTURE: "TABLE",
  SKETCH_DEVELOPMENT: "CARDS",
  SKETCH_REVIEW: "TABLE",
  PROTOTYPE_DEVELOPMENT: "CARDS",
  PROTOTYPE_ITERATION: "TABLE",
  PROTOTYPE_VALIDATION: "TABLE",
  FINAL_PROTOTYPE_DOCUMENTATION: "CARDS",
  COSTING_MARKET_LINKAGE: "TABLE",
  POST_WORKSHOP_FOLLOWUP: "TABLE"
};

const HEAVY_STAGES = new Set([
  "PROTOTYPE_DEVELOPMENT",
  "FINAL_PROTOTYPE_DOCUMENTATION",
  "SKETCH_DEVELOPMENT",
  "MARKET_SURVEY_CAPTURE"
]);

const MAP_SECTION = section({ special: "MAP", heading: "Where the workshop was held and where its artisans live" });
const OUTCOME_FIGURES = section({
  special: "CHART",
  heading: "Outcomes in figures",
  figures: ["OUTPUT_COUNTS", "PROTOTYPE_STATUS"]
});

function standardSections(opts: { photos?: boolean; photoColumns?: number; figures?: boolean } = {}): TemplateSection[] {
  const photos = opts.photos ?? true;
  const photoColumns = opts.photoColumns ?? 2;
  const figures = opts.figures ?? false;
  const out: TemplateSection[] = [
    section({ special: "COVER" }),
    section({ special: "TOC" }),
    section({ special: "SUMMARY_METRICS", heading: "Workshop at a glance" })
  ];
  if (figures) out.push(OUTCOME_FIGURES);
  for (const stageKey of NARRATIVE_ORDER) {
    out.push(
      section({
        stageKey,
        presentation: TABULAR[stageKey] ?? "AUTO",
        includePhotos: photos,
        photoColumns,
        pageBreakBefore: HEAVY_STAGES.has(stageKey)
      })
    );
    if (figures && stageKey === "WORKSHOP_PLAN_PARTICIPANTS_OPENING") out.push(MAP_SECTION);
  }
  out.push(section({ special: "SIGNATURES", pageBreakBefore: true }));
  return out;
}

const TRANSCRIPT_ANNEXURE = section({
  special: "ANNEXURE_TRANSCRIPTS",
  heading: "Annexure — Recordings and transcripts",
  pageBreakBefore: true
});
const QUESTIONNAIRE_ANNEXURE = section({
  special: "ANNEXURE_QUESTIONNAIRES",
  heading: "Annexure — Questionnaire responses",
  pageBreakBefore: true
});

const DCH_THEME: ReportTheme = {
  ...DEFAULT_THEME,
  accent: "1F3864",
  accent_soft: "2F5496",
  table_header_fill: "1F3864",
  zebra_fill: "F2F5FA",
  rule: "B8C4D9"
};
const DIC_THEME: ReportTheme = {
  ...DEFAULT_THEME,
  accent: "1B4332",
  accent_soft: "2D6A4F",
  table_header_fill: "1B4332",
  zebra_fill: "EFF6F1",
  rule: "BBD3C4"
};
const AGENCY_THEME: ReportTheme = {
  ...DEFAULT_THEME,
  accent: "6B2737",
  accent_soft: "8C3A4D",
  table_header_fill: "6B2737",
  zebra_fill: "FBF1F3",
  rule: "E0C3CB"
};
const CATALOGUE_THEME: ReportTheme = {
  ...DEFAULT_THEME,
  accent: "1B1B1B",
  accent_soft: "4A4A4A",
  table_header_fill: "2B2B2B",
  zebra_fill: "F5F5F4",
  rule: "D6D3D1",
  base_size_pt: 10
};

function template(partial: Partial<ReportTemplate> & Pick<ReportTemplate, "id" | "name" | "description" | "sections">): ReportTemplate {
  return {
    maxTier: "ADVANCED",
    pageSize: "A4",
    theme: DEFAULT_THEME,
    numberHeadings: true,
    organisation: "",
    showEmptyNote: true,
    ...partial
  };
}

export const TEMPLATES: readonly ReportTemplate[] = [
  template({
    id: "DCH_STANDARD",
    name: "DCH standard workshop report",
    description:
      "The full narrative report for submission to the Development Commissioner (Handicrafts): cover, contents, every stage in the reader's order, photographs, cost sheets and sign-off.",
    sections: [...standardSections({ figures: true }), QUESTIONNAIRE_ANNEXURE, TRANSCRIPT_ANNEXURE],
    organisation: "Office of the Development Commissioner (Handicrafts)",
    theme: DCH_THEME
  }),
  template({
    id: "DIC_STANDARD",
    name: "DIC standard workshop report",
    description:
      "The District Industries Centre format. The same content as the DCH report with the section names a DIC submission expects and the administrative annexure brought forward.",
    sections: [...standardSections(), QUESTIONNAIRE_ANNEXURE, TRANSCRIPT_ANNEXURE],
    organisation: "District Industries Centre",
    theme: DIC_THEME
  }),
  template({
    id: "IMPLEMENTING_AGENCY",
    name: "Implementing agency format",
    description:
      "For the implementing agency's own file: outcomes, costs and prototypes first, with the cluster and survey background reduced to an annexure.",
    sections: [
      section({ special: "COVER" }),
      section({ special: "TOC" }),
      section({ special: "SUMMARY_METRICS", heading: "Outcomes at a glance" }),
      section({ stageKey: "WORKSHOP_OUTCOMES", presentation: "NARRATIVE" }),
      section({ stageKey: "FINAL_PROTOTYPE_DOCUMENTATION", presentation: "CARDS", pageBreakBefore: true }),
      section({ stageKey: "COSTING_MARKET_LINKAGE", presentation: "TABLE" }),
      section({ stageKey: "PROTOTYPE_VALIDATION", presentation: "TABLE" }),
      section({
        stageKey: "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
        presentation: "TABLE",
        pageBreakBefore: true,
        heading: "Annexure A — Participants"
      }),
      section({
        stageKey: "CLUSTER_CRAFT_BACKGROUND",
        presentation: "KEY_VALUE",
        heading: "Annexure B — Cluster and craft background"
      }),
      section({ stageKey: "MARKET_SURVEY_CAPTURE", presentation: "TABLE", heading: "Annexure C — Market survey" }),
      section({ stageKey: "POST_WORKSHOP_FOLLOWUP", presentation: "TABLE" }),
      section({ special: "SIGNATURES", pageBreakBefore: true }),
      QUESTIONNAIRE_ANNEXURE,
      TRANSCRIPT_ANNEXURE
    ],
    theme: AGENCY_THEME
  }),
  template({
    id: "COMPACT_SUMMARY",
    name: "Compact summary",
    description:
      "A few pages for a review meeting: what was done, what came out of it and what it cost. Basic-tier fields only, one photograph per prototype.",
    maxTier: "BASIC",
    sections: [
      section({ special: "COVER" }),
      section({ special: "SUMMARY_METRICS", heading: "Workshop at a glance" }),
      section({ stageKey: "WORKSHOP_SETUP", presentation: "KEY_VALUE" }),
      section({ stageKey: "CLUSTER_CRAFT_BACKGROUND", presentation: "NARRATIVE", includePhotos: false }),
      section({ stageKey: "MARKET_ANALYSIS_DIRECTION", presentation: "NARRATIVE", includePhotos: false }),
      section({ stageKey: "DESIGN_BRIEF", presentation: "NARRATIVE", includePhotos: false }),
      section({ stageKey: "FINAL_PROTOTYPE_DOCUMENTATION", presentation: "TABLE", photoColumns: 3, maxPhotos: 6 }),
      section({ stageKey: "COSTING_MARKET_LINKAGE", presentation: "TABLE", includePhotos: false }),
      section({ stageKey: "WORKSHOP_OUTCOMES", presentation: "NARRATIVE", includePhotos: false }),
      section({ special: "SIGNATURES" }),
      QUESTIONNAIRE_ANNEXURE,
      TRANSCRIPT_ANNEXURE
    ],
    theme: DCH_THEME
  }),
  template({
    id: "DETAILED_TECHNICAL",
    name: "Detailed technical report",
    description:
      "Everything captured, including the Advanced tier: process sequences, material specifications, iteration histories, quality assessments and the full media annexure. The archival copy.",
    sections: [
      ...standardSections({ photoColumns: 2, figures: true }),
      section({ special: "ANNEXURE_MEDIA", pageBreakBefore: true, heading: "Annexure — Photographic record" }),
      QUESTIONNAIRE_ANNEXURE,
      TRANSCRIPT_ANNEXURE,
      section({ special: "COMPLETENESS", heading: "Annexure — Data completeness" })
    ],
    theme: DCH_THEME
  }),
  template({
    id: "PHOTO_CATALOGUE",
    name: "Photo catalogue",
    description:
      "A buyer-facing catalogue: the final products, large, with their dimensions, materials and prices. Almost no prose.",
    sections: [
      section({ special: "COVER" }),
      section({ stageKey: "FINAL_PROTOTYPE_DOCUMENTATION", presentation: "CARDS", photoColumns: 1, heading: "Products" }),
      section({
        stageKey: "COSTING_MARKET_LINKAGE",
        presentation: "TABLE",
        includePhotos: false,
        heading: "Price list",
        pageBreakBefore: true,
        // No cost breakdown in a document that goes to a buyer — see the server's note.
        includeFigures: false
      }),
      section({
        stageKey: "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
        presentation: "TABLE",
        includePhotos: false,
        heading: "The makers"
      }),
      QUESTIONNAIRE_ANNEXURE,
      TRANSCRIPT_ANNEXURE
    ],
    numberHeadings: false,
    theme: CATALOGUE_THEME
  })
];

export const TEMPLATE_IDS: readonly string[] = TEMPLATES.map((t) => t.id);

/** `report_templates.template`: an unknown id falls back to the first template. */
export function templateById(id: string): ReportTemplate {
  return TEMPLATES.find((t) => t.id === id) ?? TEMPLATES[0];
}

/** `design_workshops.resolve_template_id`: the request, then stage 20, then the header column. */
export function resolveTemplateId(requested: unknown, settings: Record<string, unknown> | null, headerTemplateId: string): string {
  for (const candidate of [requested, (settings ?? {}).templateId]) {
    const token = candidate === null || candidate === undefined || candidate === "" ? "" : String(candidate).trim();
    if (TEMPLATE_IDS.includes(token)) return token;
  }
  return headerTemplateId || "";
}

const SECTION_TOGGLES: Array<[string, SpecialSection]> = [
  ["includeTableOfContents", "TOC"],
  ["includeMediaAnnexure", "ANNEXURE_MEDIA"],
  ["includeCompletenessAnnexure", "COMPLETENESS"]
];

const TOGGLE_LABELS: Record<string, string> = {
  includeTableOfContents: "Include a table of contents",
  includeMediaAnnexure: "Include the photographic annexure",
  includeCompletenessAnnexure: "Include the completeness annexure"
};

/** `_asked`: null for a switch nobody set, else Python's truthiness of the answer. */
export function asked(settings: Record<string, unknown> | null | undefined, key: string): boolean | null {
  if (!settings) return null;
  const raw = settings[key];
  if (raw === null || raw === undefined || raw === "") return null;
  return pyTruthy(raw);
}

export function pyTruthy(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === "number") return value !== 0;
  if (typeof value === "string") return value.length > 0;
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === "object") return Object.keys(value as object).length > 0;
  return Boolean(value);
}

/** `report_templates.inert_section_toggles`. */
export function inertSectionToggles(template_: ReportTemplate, settings: Record<string, unknown> | null): string[] {
  if (!settings || !Object.keys(settings).length) return [];
  const present = new Set(template_.sections.map((s) => s.special));
  const out: string[] = [];
  for (const [key, special] of SECTION_TOGGLES) {
    if (asked(settings, key) === true && !present.has(special)) {
      out.push(
        `“${TOGGLE_LABELS[key]}” is on, but the ${template_.name} template does not carry ` +
          `that section and this switch can only leave one out — the file was generated ` +
          `without it. Choose a template that includes it, or turn the switch off.`
      );
    }
  }
  return out;
}

export type CustomSectionRef = { key: string; title: string; stageKey: string; sortOrder: number };

/** `report_templates.apply_report_settings`. */
export function applyReportSettings(
  template_: ReportTemplate,
  settings: Record<string, unknown> | null,
  opts: { includePhotographs?: boolean | null; includeAiLayers?: boolean | null; customSections?: CustomSectionRef[] } = {}
): ReportTemplate {
  const customSections = opts.customSections ?? [];
  const hasSettings = Boolean(settings && Object.keys(settings).length);
  if (!hasSettings && (opts.includePhotographs ?? null) === null && !opts.includeAiLayers && !customSections.length) {
    return template_;
  }
  let sections = [...template_.sections];
  for (const [key, special] of SECTION_TOGGLES) {
    if (asked(settings, key) === false) sections = sections.filter((s) => s.special !== special);
  }
  const excluded = settings ? settings.excludedStages : undefined;
  if (Array.isArray(excluded)) {
    const drop = new Set(excluded.map((k) => String(k).trim()).filter(Boolean));
    if (drop.size) sections = sections.filter((s) => !(s.stageKey && drop.has(s.stageKey)));
  }
  if (customSections.length) {
    const ordered = [...customSections].sort(
      (a, b) => (a.sortOrder || 0) - (b.sortOrder || 0) || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0)
    );
    for (const definition of ordered) {
      const key = definition.key || "";
      if (!key) continue;
      const block = section({ special: "CUSTOM_SECTION", customKey: key, heading: definition.title || "" });
      const stageKey = definition.stageKey || "";
      const anchor = sections.findIndex((s) => Boolean(s.stageKey) && s.stageKey === stageKey);
      if (anchor >= 0) {
        let at = anchor + 1;
        while (at < sections.length && sections[at].special === "CUSTOM_SECTION") at += 1;
        sections.splice(at, 0, block);
        continue;
      }
      const completeness = sections.findIndex((s) => s.special === "COMPLETENESS");
      sections.splice(completeness >= 0 ? completeness : sections.length, 0, { ...block, pageBreakBefore: true });
    }
  }
  if (opts.includeAiLayers) {
    const annexure = section({ special: "ANNEXURE_AI_LAYERS", pageBreakBefore: true });
    const completeness = sections.findIndex((s) => s.special === "COMPLETENESS");
    sections.splice(completeness >= 0 ? completeness : sections.length, 0, annexure);
  }
  let photos = opts.includePhotographs ?? null;
  if (photos === null) photos = asked(settings, "includePhotographs");
  const rawColumns = settings ? settings.photoColumns : undefined;
  let columns: number | null = null;
  if (rawColumns !== null && rawColumns !== undefined && rawColumns !== "") {
    const parsed = typeof rawColumns === "number" ? Math.trunc(rawColumns) : Number.parseInt(String(rawColumns).trim(), 10);
    columns = Number.isFinite(parsed) && /^\s*[+-]?\d+\s*$/.test(String(rawColumns)) ? Math.max(1, Math.min(4, parsed)) : null;
    if (typeof rawColumns === "number" && Number.isFinite(rawColumns)) columns = Math.max(1, Math.min(4, Math.trunc(rawColumns)));
  }
  if (photos !== null || columns !== null) {
    sections = sections.map((s) => ({
      ...s,
      ...(photos === null ? {} : { includePhotos: Boolean(s.includePhotos && photos) }),
      ...(columns === null ? {} : { photoColumns: columns })
    }));
  }
  const numbered = asked(settings, "numberHeadings");
  return { ...template_, sections, ...(numbered === null ? {} : { numberHeadings: numbered }) };
}
