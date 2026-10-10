/**
 * The four parts of a report that are not a stage: the designer's own sections, the questionnaire
 * sittings, the transcripts and the accepted machine-assisted text. Ported from
 * `report_custom_sections.py`, `report_questionnaires.py`, `report_annexures.py` and
 * `report_ai_layers.py`, sentence for sentence, because every one of these sentences is printed in a
 * file a ministry receives and the browser's copy must print the same words.
 *
 * Their SOURCES are the snake_case items `GET /design-workshops/{id}/report/sources` returns
 * (`dataclasses.asdict` of the server's own item types), kept on the device by
 * `lib/offlineReport/sourcesCache.ts`; the custom sections come from the definition and the answers
 * the draft already holds.
 */

import {
  DocumentBuilder,
  cleanText,
  collapseSpace,
  column,
  groupThousands,
  paragraph,
  pyStrip,
  runsOf,
  table,
  type Block,
  type Run,
  type TableBlock
} from "@/lib/offlineReport/model";
import { plainFromStored, plainRuns, storedTextDocument } from "@/lib/offlineReport/richText";

/* ────────────────────────────────────────────────────────────────────────────
 * The designer's own sections — report_custom_sections.py
 * ──────────────────────────────────────────────────────────────────────────── */

export const CUSTOM_NOT_RECORDED = "Not recorded.";
export const RETIRED_NOTE = "no longer asked";
const MAX_ROWS_PER_SECTION = 200;
const TIER_RANK: Record<string, number> = { BASIC: 0, STANDARD: 1, ADVANCED: 2 };
export const ALL_TIERS = 2;

export type CustomReportField = {
  key: string;
  label: string;
  type: string;
  unit: string;
  options: Array<[string, string]>;
  required: boolean;
  retired: boolean;
  tier: string;
};

export type CustomSectionItem = {
  key: string;
  title: string;
  stageKey: string;
  description: string;
  sortOrder: number;
  fields: CustomReportField[];
  values: Record<string, unknown>;
};

function tierRank(field: CustomReportField): number {
  return TIER_RANK[String(field.tier).toUpperCase()] ?? 0;
}

export function hasAnswer(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === "string") return Boolean(pyStrip(value));
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === "object") return Object.keys(value as object).length > 0;
  return true;
}

function fieldsAt(item: CustomSectionItem, maxTierRank: number): CustomReportField[] {
  return item.fields.filter((f) => tierRank(f) <= maxTierRank);
}

export function hasContentAt(item: CustomSectionItem, maxTierRank = ALL_TIERS): boolean {
  return fieldsAt(item, maxTierRank).some((f) => hasAnswer(item.values[f.key]) || (f.required && !f.retired));
}

export function answeredCountAt(item: CustomSectionItem, maxTierRank = ALL_TIERS): number {
  return fieldsAt(item, maxTierRank).filter((f) => hasAnswer(item.values[f.key])).length;
}

export function sectionPrints(item: CustomSectionItem | undefined, maxTierRank = ALL_TIERS): boolean {
  return item !== undefined && hasContentAt(item, maxTierRank);
}

export function sectionsHiddenByTier(items: Array<CustomSectionItem | undefined>, maxTierRank: number): CustomSectionItem[] {
  return items.filter(
    (item): item is CustomSectionItem => sectionPrints(item, ALL_TIERS) && !sectionPrints(item, maxTierRank)
  );
}

/** `report_custom_sections.custom_scoring`: the stage's own fields and answers, for completeness. */
export function customScoring(items: CustomSectionItem[], stageKey: string): [CustomReportField[], Record<string, unknown>] {
  const fields: CustomReportField[] = [];
  const values: Record<string, unknown> = {};
  const matching = items
    .filter((i) => i.stageKey === stageKey)
    .sort((a, b) => a.sortOrder - b.sortOrder || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
  for (const item of matching) {
    fields.push(...item.fields);
    Object.assign(values, item.values);
  }
  return [fields, values];
}

function optionLabel(field: CustomReportField, value: unknown): string {
  const token = String(value);
  const found = field.options.find(([v]) => v === token);
  return found ? found[1] || token : token;
}

/** `report_custom_sections.display_value`; `formatValue` is the builder's `format_value`. */
function displayValue(
  field: CustomReportField,
  value: unknown,
  formatValue: (type: string, unit: string, value: unknown) => string | null
): string {
  if (value === null || value === undefined || value === "" || (Array.isArray(value) && !value.length)) return "";
  if (field.type === "ENUM") return optionLabel(field, value);
  if (field.type === "MULTI_ENUM") {
    if (Array.isArray(value)) return value.map((v) => optionLabel(field, v)).join(", ");
    return optionLabel(field, value);
  }
  const formatted = formatValue(field.type, field.unit, value);
  return formatted === null ? cleanText(value) : formatted;
}

function labelOf(field: CustomReportField): string {
  return field.retired ? `${field.label} (${RETIRED_NOTE})` : field.label;
}

function customSectionBlocks(
  item: CustomSectionItem,
  maxTierRank: number,
  formatValue: (type: string, unit: string, value: unknown) => string | null
): Block[] {
  const pairs: Array<[string, string]> = [];
  const prose: Array<[string, string]> = [];
  let printed = 0;
  let truncated = false;
  const printedFields = fieldsAt(item, maxTierRank).filter((f) => !f.retired || hasAnswer(item.values[f.key]));
  for (const field of printedFields) {
    if (printed >= MAX_ROWS_PER_SECTION) {
      truncated = true;
      break;
    }
    printed += 1;
    const text = displayValue(field, item.values[field.key], formatValue);
    if (field.type === "LONG_TEXT") {
      if (pyStrip(text)) prose.push([labelOf(field), text]);
      else if (field.required && !field.retired) pairs.push([labelOf(field), CUSTOM_NOT_RECORDED]);
      continue;
    }
    if (pyStrip(text)) pairs.push([labelOf(field), text]);
    else if (field.required && !field.retired) pairs.push([labelOf(field), CUSTOM_NOT_RECORDED]);
  }
  const blocks: Block[] = [];
  if (pairs.length) {
    blocks.push({
      type: "KEYVALUE",
      pairs: pairs.map(([label, value]) => [cleanText(label), runsOf(value)]),
      columns: 1,
      label_width_pct: 30
    });
  }
  for (const [label, text] of prose) {
    blocks.push(paragraph(runsOf(label, { bold: true }), "BODY", "LEFT"));
    for (const chunk of cleanText(text).split("\n\n")) {
      if (chunk.trim()) blocks.push(paragraph(runsOf(pyStrip(chunk)), "BODY"));
    }
  }
  if (truncated) {
    blocks.push(
      paragraph(
        runsOf(
          `[Answers truncated after ${MAX_ROWS_PER_SECTION} questions. The full set is held ` +
            `against the workshop in the repository.]`
        ),
        "NOTE"
      )
    );
  }
  return blocks;
}

/** `report_custom_sections.append_custom_section`. */
export function appendCustomSection(
  doc: DocumentBuilder,
  item: CustomSectionItem | undefined,
  opts: { heading: string; numbered: boolean; pageBreakBefore: boolean; maxTierRank: number },
  formatValue: (type: string, unit: string, value: unknown) => string | null
): number {
  if (!item || !sectionPrints(item, opts.maxTierRank)) return 0;
  if (opts.pageBreakBefore) doc.add({ type: "PAGEBREAK" });
  doc.heading(opts.heading || item.title, 1, opts.numbered);
  if (pyStrip(item.description)) doc.para(item.description, "LEAD");
  for (const block of customSectionBlocks(item, opts.maxTierRank, formatValue)) doc.add(block);
  return answeredCountAt(item, opts.maxTierRank);
}

/** The definition's API shape, as `lib/customSections.ts` holds it. */
export type CustomDefinitionInput = {
  sections: Array<{
    key: string;
    title: string;
    stageKey: string;
    description: string;
    sortOrder: number;
    retired: boolean;
    fields: Array<{
      key: string;
      label: string;
      type: string;
      tier: string;
      required: boolean;
      unit: string;
      options: Array<{ value: string; label: string }>;
      retired: boolean;
    }>;
  }>;
} | null;

/** `design_workshops.report_custom_section_items`: the sections, and the warning about empty ones. */
export function customSectionItems(
  definition: CustomDefinitionInput,
  valuesByStage: Record<string, Record<string, unknown>>
): [CustomSectionItem[], string[]] {
  const items: CustomSectionItem[] = [];
  for (const section of definition?.sections ?? []) {
    const item: CustomSectionItem = {
      key: section.key,
      title: section.title,
      stageKey: section.stageKey,
      description: section.description ?? "",
      sortOrder: section.sortOrder ?? 0,
      fields: section.fields.map((f) => ({
        key: f.key,
        label: f.label,
        type: f.type,
        unit: f.unit ?? "",
        options: (f.options ?? []).map((o) => [o.value, o.label || o.value] as [string, string]),
        required: Boolean(f.required),
        tier: f.tier,
        retired: Boolean(f.retired || section.retired)
      })),
      values: valuesByStage[section.stageKey] ?? {}
    };
    if (section.retired && !answeredCountAt(item)) continue;
    items.push(item);
  }
  const warnings: string[] = [];
  const unanswered = items.filter((item) => !hasContentAt(item));
  if (unanswered.length) {
    const titles = unanswered.map((i) => i.title).sort(pyCompare);
    warnings.push(
      `${unanswered.length} of this workshop's own section(s) have no answers recorded and are ` +
        `not in this file: ` +
        titles.slice(0, 4).join(", ") +
        (unanswered.length > 4 ? "…" : "")
    );
  }
  return [items, warnings];
}

/** Python's default string ordering: by code point, not by locale. */
export function pyCompare(a: string, b: string): number {
  const x = Array.from(a);
  const y = Array.from(b);
  for (let i = 0; i < Math.min(x.length, y.length); i += 1) {
    const d = (x[i].codePointAt(0) ?? 0) - (y[i].codePointAt(0) ?? 0);
    if (d) return d;
  }
  return x.length - y.length;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Questionnaires — report_questionnaires.py
 * ──────────────────────────────────────────────────────────────────────────── */

export type QuestionnaireAnswer = {
  prompt: string;
  answer_text: string;
  notes: string;
  section_code: string;
  section_title: string;
  is_required: boolean;
  is_retired: boolean;
};
export type QuestionnaireSitting = {
  entry_id: string;
  title: string;
  respondent_name: string;
  source: string;
  notes: string;
  recorded_at: string;
  recorded_by: string;
  answers: QuestionnaireAnswer[];
};
export type QuestionnaireItem = {
  questionnaire_id: string;
  title: string;
  description: string;
  version: number;
  source_filename: string;
  kind: string;
  question_count: number;
  sittings: QuestionnaireSitting[];
};

const Q_DEFAULT_HEADING = "Annexure — Questionnaire responses";
const MAX_SITTINGS_PER_QUESTIONNAIRE = 40;
const MAX_ROWS_PER_SITTING = 400;
const Q_NOT_RECORDED = "Not recorded.";
const KIND_LABELS: Record<string, string> = { WORKSHOP_INTERVIEW: "Workshop interview", MARKET_SURVEY: "Market survey" };
const KIND_STAGE_KEYS: Record<string, string> = {
  WORKSHOP_INTERVIEW: "EXISTING_PRODUCTS_BASELINE",
  MARKET_SURVEY: "MARKET_SURVEY_CAPTURE"
};

function kindLabel(value: string): string {
  if (!value) return "Kind not stated";
  return KIND_LABELS[value] ?? value;
}

function plainAnswer(answer: QuestionnaireAnswer): string {
  return pyStrip(String(plainFromStored(answer.answer_text || "")));
}

function answerHas(answer: QuestionnaireAnswer): boolean {
  return Boolean(plainAnswer(answer));
}

function answerPrints(answer: QuestionnaireAnswer): boolean {
  return answerHas(answer) || (answer.is_required && !answer.is_retired);
}

function sectionLabel(answer: QuestionnaireAnswer): string {
  return [answer.section_code, answer.section_title].filter(Boolean).join(" — ");
}

function sittingLabel(sitting: QuestionnaireSitting): string {
  return pyStrip(sitting.respondent_name) || pyStrip(sitting.title) || `Sitting ${sitting.entry_id.slice(0, 8)}`;
}

function answeredCount(sitting: QuestionnaireSitting): number {
  return sitting.answers.filter(answerHas).length;
}

function printedSittings(item: QuestionnaireItem): QuestionnaireSitting[] {
  return item.sittings.filter((s) => answeredCount(s) > 0);
}

function questionRuns(answer: QuestionnaireAnswer): Run[] {
  const runs = runsOf(answer.prompt || "—");
  if (answer.is_retired) runs.push(...runsOf(` (${RETIRED_NOTE})`, { italic: true }));
  return runs;
}

function answerRuns(answer: QuestionnaireAnswer): Run[] {
  if (!answerHas(answer)) return runsOf(Q_NOT_RECORDED, { italic: true });
  const document = storedTextDocument(answer.answer_text || "");
  const runs = document === null ? runsOf(plainAnswer(answer)) : plainRuns(document);
  const note = pyStrip(answer.notes || "");
  if (note) runs.push(...runsOf(`  Note: ${note}`, { italic: true }));
  return runs;
}

function sittingProvenance(sitting: QuestionnaireSitting): string {
  const source = ({ UPLOAD: "recorded on the uploaded spreadsheet", APP: "recorded in the app" } as Record<string, string>)[
    String(sitting.source).toUpperCase()
  ] ?? "";
  return [
    `${answeredCount(sitting)} question(s) answered`,
    source,
    sitting.recorded_at ? sitting.recorded_at.slice(0, 10) : "",
    sitting.recorded_by ? `recorded by ${sitting.recorded_by}` : ""
  ]
    .filter(Boolean)
    .join(" · ");
}

function stageGroup(item: QuestionnaireItem, stageTitles: Record<string, [number, string]>): [number, string, string] {
  if (!item.kind) return [10_000, "", ""];
  const stageKey = KIND_STAGE_KEYS[item.kind];
  const label = kindLabel(item.kind);
  if (!stageKey) return [9_000, label, `filed as ${label}`];
  const [number, title] = stageTitles[stageKey] ?? [8_000, stageKey];
  return [number, `${label} — stage ${number}, ${title}`, `filed as ${label}, under stage ${number} (${title})`];
}

function questionnaireProvenance(item: QuestionnaireItem, stageTitles: Record<string, [number, string]>): string {
  const printed = printedSittings(item);
  const parts = [
    stageGroup(item, stageTitles)[2],
    `${printed.length} sitting(s)`,
    item.question_count ? `${item.question_count} question(s)` : "",
    `version ${item.version}`,
    item.source_filename ? `from ${item.source_filename}` : "",
    `questionnaire ${item.questionnaire_id}`
  ].filter(Boolean);
  return "The designer's own questionnaire, attached to this workshop · " + parts.join(" · ");
}

function questionnaireIndex(items: QuestionnaireItem[]): TableBlock {
  return table(
    [
      column("Questionnaire", 36),
      column("Kind", 18),
      column("Questions", 14, { numeric: true }),
      column("Sittings", 14, { numeric: true }),
      column("Answers recorded", 18, { numeric: true })
    ],
    items.map((item) => [
      runsOf(item.title || "Untitled questionnaire"),
      runsOf(kindLabel(item.kind)),
      runsOf(item.question_count ? groupThousands(item.question_count) : "—"),
      runsOf(groupThousands(printedSittings(item).length)),
      runsOf(groupThousands(printedSittings(item).reduce((sum, s) => sum + answeredCount(s), 0)))
    ]),
    "Questionnaires attached to this workshop and the sittings recorded against them."
  );
}

function sittingBlocks(sitting: QuestionnaireSitting): Block[] {
  const blocks: Block[] = [];
  let rows: Run[][][] = [];
  let current = "";
  let printed = 0;
  let truncated = false;
  const flush = () => {
    if (!rows.length) return;
    if (current) blocks.push(paragraph(runsOf(current, { bold: true }), "BODY", "LEFT"));
    blocks.push(table([column("Question", 46), column("Answer", 54)], rows));
    rows = [];
  };
  for (const answer of sitting.answers.filter(answerPrints)) {
    if (printed >= MAX_ROWS_PER_SITTING) {
      truncated = true;
      break;
    }
    const label = sectionLabel(answer);
    if (label !== current) {
      flush();
      current = label;
    }
    rows.push([questionRuns(answer), answerRuns(answer)]);
    printed += 1;
  }
  flush();
  if (truncated) {
    blocks.push(
      paragraph(
        runsOf(
          `[Answers truncated after ${MAX_ROWS_PER_SITTING} questions. The full set is held ` +
            `against the questionnaire in the repository.]`
        ),
        "NOTE"
      )
    );
  }
  return blocks;
}

/** `report_questionnaires.append_questionnaire_annexure`. */
export function appendQuestionnaireAnnexure(
  doc: DocumentBuilder,
  items: QuestionnaireItem[],
  opts: { heading: string; numbered: boolean; pageBreakBefore: boolean },
  stageTitles: Record<string, [number, string]>
): number {
  const printed = items.filter((item) => printedSittings(item).length > 0);
  if (!printed.length) return 0;
  if (opts.pageBreakBefore) doc.add({ type: "PAGEBREAK" });
  doc.heading(opts.heading || Q_DEFAULT_HEADING, 1, opts.numbered);
  doc.para(
    "The questionnaires this workshop's designer built and attached to it, and every sitting " +
      "recorded against them. Each question is printed in the wording the answer was given under: " +
      "a question reworded after it was answered is shown here as it was asked, marked " +
      `“${RETIRED_NOTE}”. A required question left blank is printed as “${Q_NOT_RECORDED}” so that a ` +
      "gap in the fieldwork is visible as a gap.",
    "LEAD"
  );
  doc.add(questionnaireIndex(printed));
  // `grouped_by_stage`: a stable sort by the group's rank, then runs of one heading.
  const keyed = printed
    .map((item, index) => ({ item, index, rank: stageGroup(item, stageTitles)[0] }))
    .sort((a, b) => a.rank - b.rank || a.index - b.index)
    .map((entry) => entry.item);
  const groups: Array<[string, QuestionnaireItem[]]> = [];
  for (const item of keyed) {
    const heading = stageGroup(item, stageTitles)[1];
    if (groups.length && groups[groups.length - 1][0] === heading) groups[groups.length - 1][1].push(item);
    else groups.push([heading, [item]]);
  }
  for (const [stageHeading, group] of groups) {
    if (stageHeading) doc.heading(stageHeading, 2, opts.numbered);
    const depth = stageHeading ? 3 : 2;
    for (const item of group) {
      doc.heading(item.title || "Untitled questionnaire", depth, opts.numbered);
      doc.para(questionnaireProvenance(item, stageTitles), "NOTE");
      doc.para(item.description);
      const sittings = printedSittings(item);
      const dropped = sittings.length - MAX_SITTINGS_PER_QUESTIONNAIRE;
      for (const sitting of sittings.slice(0, MAX_SITTINGS_PER_QUESTIONNAIRE)) {
        doc.heading(sittingLabel(sitting), depth + 1, opts.numbered);
        doc.para(sittingProvenance(sitting), "NOTE");
        doc.para(sitting.notes);
        for (const block of sittingBlocks(sitting)) doc.add(block);
      }
      if (dropped > 0) {
        doc.para(
          `[${dropped} further sitting(s) were recorded against this questionnaire and ` +
            `are not printed here. The full set is held in the repository.]`,
          "NOTE"
        );
      }
    }
  }
  return printed.length;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Transcripts — report_annexures.py
 * ──────────────────────────────────────────────────────────────────────────── */

export type TranscriptItem = {
  media_id: string;
  stage_key: string;
  stage_number: number;
  stage_title: string;
  entity_key: string;
  field_key: string;
  field_label: string;
  filename: string;
  recorded_at: string;
  duration_seconds: number | null;
  status: string;
  text: string;
};

const T_DEFAULT_HEADING = "Annexure — Recordings and transcripts";
const MAX_PARAGRAPHS_PER_TRANSCRIPT = 1200;
const LOOSE_LABEL_RE = /\*\*\s*([^*\n]{1,60}?)\s*\*\*\s*:/g;
const SPEAKER_LINE_RE = /^\*\*([^*\n]{1,60}?):\*\*\s*(.*)$/;
const RULE_RE = /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/;
const HEADING_RE = /^\s*#{1,6}\s+(\S.*?)\s*#*\s*$/;
const BULLET_RE = /^\s*[-*+]\s+(?=\S)/;
const CODE_RE = /`([^`\n]+)`/g;
const EMPHASIS_RE = /\*\*\s*([^*\n]+?)\s*\*\*|\*\s*([^*\n]+?)\s*\*/g;

function normalisedLines(text: string): string[] {
  const lines: string[] = [];
  for (const raw of String(text || "").replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    if (RULE_RE.test(raw)) {
      lines.push("");
      continue;
    }
    const heading = HEADING_RE.exec(raw);
    if (heading) {
      lines.push(pyStrip(heading[1].replace(CODE_RE, "$1")));
      continue;
    }
    const line = pyStrip(raw).replace(BULLET_RE, "• ").replace(CODE_RE, "$1");
    lines.push(line.replace(LOOSE_LABEL_RE, "**$1:**"));
  }
  return lines;
}

export function transcriptPlainText(text: string): string {
  return collapseSpace(String(text || "").replace(EMPHASIS_RE, (_m, a: string | undefined, b: string | undefined) => a || b || ""));
}

function speakerCount(text: string): number {
  const labels = new Set<string>();
  for (const line of normalisedLines(text)) {
    const match = SPEAKER_LINE_RE.exec(line);
    if (match) labels.add(pyStrip(match[1]).toLowerCase());
  }
  if (labels.size) return labels.size;
  return transcriptPlainText(text) ? 1 : 0;
}

function wordCount(text: string): number {
  const plain = transcriptPlainText(text);
  return plain ? plain.split(" ").length : 0;
}

export function durationText(seconds: number | null): string {
  if (seconds === null || seconds === undefined || seconds <= 0) return "";
  const total = Math.trunc(seconds);
  const remainder = total % 60;
  let minutes = Math.floor(total / 60);
  const hours = Math.floor(minutes / 60);
  minutes %= 60;
  if (hours) return `${hours} h ${String(minutes).padStart(2, "0")} min`;
  if (minutes) return `${minutes} min ${String(remainder).padStart(2, "0")} s`;
  return `${remainder} s`;
}

function runsForLine(line: string): Run[] {
  const pieces: Run[] = [];
  let position = 0;
  for (const match of line.matchAll(EMPHASIS_RE)) {
    const start = match.index ?? 0;
    if (start > position) pieces.push(...runsOf(line.slice(position, start)));
    const [, bold, italic] = match;
    pieces.push(...(bold ? runsOf(bold, { bold: true }) : runsOf(italic, { italic: true })));
    position = start + match[0].length;
  }
  if (position < line.length) pieces.push(...runsOf(line.slice(position)));
  return pieces;
}

/** `report_annexures.transcript_body_blocks`. */
export function transcriptBodyBlocks(text: string): Block[] {
  const blocks: Block[] = [];
  let truncated = false;
  for (const line of normalisedLines(text)) {
    if (blocks.length >= MAX_PARAGRAPHS_PER_TRANSCRIPT) {
      truncated = true;
      break;
    }
    if (!pyStrip(line)) {
      if (blocks.length && blocks[blocks.length - 1].type !== "SPACER") blocks.push({ type: "SPACER", height_pct: 1.5 });
      continue;
    }
    const match = SPEAKER_LINE_RE.exec(line);
    const runs = match
      ? [...runsOf(`${pyStrip(match[1])}: `, { bold: true }), ...runsForLine(pyStrip(match[2]))]
      : runsForLine(line);
    if (runs.length) blocks.push(paragraph(runs, "BODY", "LEFT"));
  }
  if (truncated) {
    blocks.push(
      paragraph(
        runsOf(
          `[Transcript truncated after ${MAX_PARAGRAPHS_PER_TRANSCRIPT} paragraphs. The ` +
            `full text is held against the recording in the repository.]`
        ),
        "NOTE"
      )
    );
  }
  return blocks;
}

const T_SPEAKER_NOTE =
  "The speaker labels in this transcript — who is saying which line — were decided by the machine " +
  "that transcribed the recording, not by anybody who was present. Nothing recorded how many people " +
  "were speaking or who they were: the labels are that machine's separation of the voices it heard, " +
  "and it can merge two speakers into one or split one across two. The numbering is by order of first " +
  "speaking and is not a name. Check a line against the recording before attributing it to a " +
  "particular person.";

function transcriptLabel(item: TranscriptItem): string {
  return item.field_label || item.filename || item.media_id;
}

/** `report_annexures.append_transcript_annexure`. */
export function appendTranscriptAnnexure(
  doc: DocumentBuilder,
  items: TranscriptItem[],
  opts: { heading: string; numbered: boolean; pageBreakBefore: boolean }
): number {
  const printed = items.filter((item) => Boolean(item.text && pyStrip(item.text)));
  if (!printed.length) return 0;
  if (opts.pageBreakBefore) doc.add({ type: "PAGEBREAK" });
  doc.heading(opts.heading || T_DEFAULT_HEADING, 1, opts.numbered);
  doc.para(
    "The recordings made during this workshop, transcribed in full. The text is produced by " +
      "automatic speech recognition and lightly edited; it is the machine's reading of the audio " +
      "rather than a certified transcript, and the recordings themselves remain the record.",
    "LEAD"
  );
  doc.add(
    table(
      [
        column("Stage", 26),
        column("Recording", 32),
        column("Length", 14, { align: "RIGHT" }),
        column("Speakers", 12, { numeric: true }),
        column("Words", 16, { numeric: true })
      ],
      printed.map((item) => [
        runsOf(item.stage_number ? `${item.stage_number}. ${item.stage_title}` : item.stage_title),
        runsOf(transcriptLabel(item)),
        runsOf(durationText(item.duration_seconds) || "—"),
        runsOf(String(speakerCount(item.text))),
        runsOf(groupThousands(wordCount(item.text)))
      ]),
      "Recordings transcribed during this workshop. The speaker count is the number of voices " +
        "the transcribing machine separated out of the audio, not a count of the people present."
    )
  );
  for (const item of printed) {
    doc.heading(transcriptLabel(item), 2, opts.numbered);
    const count = speakerCount(item.text);
    const provenance = [
      durationText(item.duration_seconds),
      count ? `${count} speaker${count !== 1 ? "s" : ""}` : "",
      item.recorded_at ? item.recorded_at.slice(0, 10) : "",
      `recording ${item.media_id}`
    ].filter(Boolean);
    doc.para("Transcribed automatically from the workshop recording · " + provenance.join(" · "), "NOTE");
    if (item.text && normalisedLines(item.text).some((line) => SPEAKER_LINE_RE.test(line))) doc.para(T_SPEAKER_NOTE, "NOTE");
    for (const block of transcriptBodyBlocks(item.text)) doc.add(block);
  }
  return printed.length;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Machine-assisted text — report_ai_layers.py
 * ──────────────────────────────────────────────────────────────────────────── */

export type AiLayerItem = {
  layer_id: string;
  kind: string;
  tier: string;
  provider: string;
  model_id: string;
  model_version: string;
  language: string;
  source_language: string;
  target_language: string;
  produced_at: string;
  accepted: boolean;
  accepted_at: string;
  accepted_by: string;
  accepted_by_id: string;
  source_kind: string;
  source_id: string;
  source_label: string;
  text: string;
  text_withheld: boolean;
};

const AI_DEFAULT_HEADING = "Annexure — Machine-assisted text, and what produced it";
const MAX_PARAGRAPHS_PER_LAYER = 1200;
const KIND_TITLES: Record<string, string> = {
  RAW_TRANSCRIPT: "Automatic transcript",
  CLEANED_TRANSCRIPT: "AI-cleaned transcript",
  SUMMARY: "AI summary",
  OCR_TEXT: "Text read automatically from a photograph",
  STRUCTURED_TEXT: "AI-structured text",
  TAGS: "AI-suggested tags",
  METADATA: "AI-extracted details",
  PROOFREAD: "AI-corrected spelling and punctuation",
  EXPANDED: "Prose written by AI from a designer's note",
  TRANSLATION: "AI translation",
  CAPTION: "AI description of a photograph or video",
  SUBTITLES: "AI subtitles, with their timings"
};
const TIER_WORDS: Record<string, string> = {
  TIER_1: "on the handset, with no connection",
  TIER_2: "on the handset, by a small model held on the device",
  TIER_3: "on the server, by a hosted model"
};
const UNRECORDED = "UNRECORDED";
const SOURCE_QUOTE_CHARS = 240;
const SPEAKER_TURN = /\*\*[^*\n]{1,60}?:\*\*/;

function kindTitle(kind: string): string {
  return KIND_TITLES[String(kind || "").toUpperCase()] || "Machine-generated text";
}

function tierWords(tier: string): string {
  return TIER_WORDS[String(tier || "").toUpperCase()] || "by a machine this report cannot identify";
}

function acceptor(item: AiLayerItem): string {
  const name = pyStrip(item.accepted_by || "");
  if (name) return name;
  const account = pyStrip(item.accepted_by_id || "");
  if (account) return `the account ${account}, whose name this report could not resolve`;
  return "somebody this report cannot identify";
}

function modelName(item: AiLayerItem): string {
  const model = pyStrip(item.model_id || "");
  const version = pyStrip(item.model_version || "");
  if (!model || model === UNRECORDED) return "the model was not recorded";
  return pyStrip(`${model} ${version}`);
}

function elide(text: string): string {
  const flat = collapseSpace(text || "");
  const chars = Array.from(flat);
  if (chars.length <= SOURCE_QUOTE_CHARS) return flat;
  return chars.slice(0, SOURCE_QUOTE_CHARS).join("").replace(/\s+$/, "") + "…";
}

function provenanceLine(item: AiLayerItem): string {
  const parts: string[] = [`Produced ${tierWords(item.tier)}`];
  const provider = pyStrip(item.provider || "");
  if (provider && provider !== UNRECORDED) parts.push(`provider ${provider}`);
  parts.push(modelName(item));
  if (pyStrip(item.target_language)) {
    const source = pyStrip(item.source_language) || UNRECORDED;
    const words =
      source === UNRECORDED ? "a language nobody recorded" : source.toLowerCase() === "multi" ? "several languages, interleaved" : source;
    parts.push(`translated from ${words} into ${pyStrip(item.target_language)}`);
  } else if (item.language && pyStrip(item.language)) {
    const language = pyStrip(item.language);
    parts.push(language.toLowerCase() === "multi" ? "several languages, interleaved" : `in ${language}`);
  }
  if (item.produced_at) parts.push(item.produced_at.slice(0, 10));
  if (item.source_kind) {
    const origin = item.source_label || item.source_id || "an unnamed source";
    const sourceKind = item.source_kind.toUpperCase();
    if (sourceKind === "MEDIA") parts.push(`made from the recording “${origin}”`);
    else if (sourceKind === "SUPPLIED_TEXT") parts.push(`made from the note “${elide(origin)}”`);
    else parts.push(`made from “${origin}”`);
  }
  const accepted = `Accepted by ${acceptor(item)}` + (item.accepted_at ? ` on ${item.accepted_at.slice(0, 10)}` : "");
  return parts.join(" · ") + ". " + accepted + ".";
}

const AI_LEAD =
  "The passages below were produced by automatic transcription, recognition or summarisation, and " +
  "each was read against its source and accepted by the person recorded beside it before it was " +
  "included. They are printed separately, and named as machine-assisted, so that nothing in the " +
  "body of this report is mistaken for a machine's words and nothing a machine wrote is mistaken " +
  "for the author's.";
const EXPANDED_NOTE =
  "This passage was written by a machine from a short note the designer made. The note itself is " +
  "quoted above as the source. Anything in this passage that is not in that note — a detail, a " +
  "reason, a connection between two things — was supplied by the model and was not recorded in the " +
  "field. Treat the note as the record and this as a reading of it, and check any specific claim " +
  "against the workshop's own material before quoting it.";
const AI_SPEAKER_NOTE =
  "The speaker labels in this passage — who is saying which line — were decided by the machine that " +
  "transcribed the recording, not by anybody who was present. Nothing recorded how many people were " +
  "speaking or who they were: the labels are that machine's separation of the voices it heard, and " +
  "it can merge two speakers into one or split one across two. The numbering is by order of first " +
  "speaking and is not a name. Check a line against the recording before attributing it to a " +
  "particular person.";
const WITHHELD_NOTE =
  "The text of this passage is not printed in this copy. It was produced from a recording that the " +
  "account which generated this report may not read: on this system, permission to open a workshop " +
  "and permission to read a particular recording are granted separately. What produced the passage, " +
  "and who accepted it, are stated above and are complete. For a copy carrying the text, ask the " +
  "colleague who uploaded the recording to generate the report, or to grant access to their media.";

/** `report_ai_layers.append_ai_layer_annexure`. */
export function appendAiLayerAnnexure(
  doc: DocumentBuilder,
  items: AiLayerItem[],
  opts: { heading: string; numbered: boolean; pageBreakBefore: boolean }
): number {
  const hasText = (item: AiLayerItem) => Boolean(item.text && pyStrip(item.text));
  const printed = items.filter((item) => item.accepted && (hasText(item) || item.text_withheld));
  if (!printed.length) return 0;
  if (opts.pageBreakBefore) doc.add({ type: "PAGEBREAK" });
  doc.heading(opts.heading || AI_DEFAULT_HEADING, 1, opts.numbered);
  doc.para(AI_LEAD, "LEAD");
  doc.add(
    table(
      [
        column("What it is", 26),
        column("Produced", 26),
        column("Model", 22),
        column("Accepted by", 16),
        column("Words", 10, { numeric: true })
      ],
      printed.map((item) => [
        runsOf(kindTitle(item.kind)),
        runsOf(tierWords(item.tier)),
        runsOf(modelName(item)),
        runsOf(acceptor(item)),
        runsOf(groupThousands(wordCount(item.text)))
      ]),
      "Text in this report that was produced by a machine, and the person who accepted each " +
        "passage. Every layer listed here was read against its source and accepted before it " +
        "was included."
    )
  );
  for (const item of printed) {
    doc.heading(kindTitle(item.kind), 2, opts.numbered);
    doc.para(provenanceLine(item), "NOTE");
    if (String(item.kind || "").toUpperCase() === "EXPANDED") doc.para(EXPANDED_NOTE, "NOTE");
    const guessed =
      String(item.source_kind || "").toUpperCase() === "MEDIA" && !item.text_withheld && Boolean(item.text) && SPEAKER_TURN.test(item.text);
    if (guessed) doc.para(AI_SPEAKER_NOTE, "NOTE");
    if (item.text_withheld) {
      doc.para(WITHHELD_NOTE, "NOTE");
      continue;
    }
    const blocks = transcriptBodyBlocks(item.text);
    if (blocks.length > MAX_PARAGRAPHS_PER_LAYER) {
      const kept = blocks.slice(0, MAX_PARAGRAPHS_PER_LAYER);
      kept.push(
        paragraph(
          runsOf(
            `[Text truncated after ${MAX_PARAGRAPHS_PER_LAYER} paragraphs. The full layer is ` +
              `held against the workshop in the repository, under ${item.layer_id}.]`
          ),
          "NOTE"
        )
      );
      for (const block of kept) doc.add(block);
    } else for (const block of blocks) doc.add(block);
  }
  return printed.length;
}

