import { countNoun } from "@/lib/format";
import { ApiError, apiFetch } from "@/lib/api";
import {
  isFilled,
  unsupportedFieldType,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwFieldType,
  type DwStage,
  type DwTier,
  type DwValue
} from "@/lib/designWorkshops";
import { isUnreachable } from "@/lib/offline";

export const CUSTOM_ENTITY_KEY = "_custom";

export const V1_CUSTOM_TYPES = [
  "TEXT",
  "LONG_TEXT",
  "INT",
  "DECIMAL",
  "MONEY",
  "PERCENT",
  "DATE",
  "TIME",
  "BOOL",
  "ENUM",
  "MULTI_ENUM",
  "TAGS"
] as const satisfies readonly DwFieldType[];

export type DwCustomFieldType = (typeof V1_CUSTOM_TYPES)[number];

const V1_TYPE_SET: ReadonlySet<string> = new Set<string>(V1_CUSTOM_TYPES);

const OPTION_TYPES: ReadonlySet<string> = new Set<string>(["ENUM", "MULTI_ENUM"]);

const BOUNDED_TYPES: ReadonlySet<string> = new Set<string>(["INT", "DECIMAL", "MONEY", "PERCENT"]);

const LENGTH_TYPES: ReadonlySet<string> = new Set<string>(["TEXT", "LONG_TEXT"]);

export function isV1CustomType(type: string): type is DwCustomFieldType {
  return V1_TYPE_SET.has(type);
}

export const MAX_CUSTOM_SECTIONS = 12;
export const MAX_CUSTOM_FIELDS_PER_SECTION = 60;
export const MAX_CUSTOM_OPTIONS = 100;
export const MAX_CUSTOM_LABEL_CHARS = 160;
export const MAX_CUSTOM_HELP_CHARS = 600;
export const MAX_CUSTOM_TITLE_CHARS = 160;
export const MAX_CUSTOM_DESCRIPTION_CHARS = 600;
export const MAX_CUSTOM_KEY_CHARS = 40;
export const MAX_CUSTOM_UNIT_CHARS = 24;

export const MAX_CUSTOM_OPTION_VALUE_CHARS = 64;

export const CUSTOM_KEY_PATTERN = /^[a-z][A-Za-z0-9]{0,39}$/;

export type DwCustomOption = { value: string; label: string };

export type DwCustomField = {
  id: string;
  key: string;
  label: string;
  type: string;
  tier: DwTier;
  required: boolean;
  help: string;
  unit: string;
  options: DwCustomOption[];
  maxLength: number;
  minValue: number | null;
  maxValue: number | null;
  sortOrder: number;
  retired: boolean;
  supersededById: string | null;
};

export type DwCustomSection = {
  id: string;
  key: string;
  stageKey: string;
  title: string;
  description: string;
  sortOrder: number;
  revision: number;
  retired: boolean;
  fields: DwCustomField[];
};

export type DwCustomDefinition = {
  customSchemaVersion: string;
  sections: DwCustomSection[];
  fetchedAt: string;
};

export type DwCustomSaveResult = DwCustomDefinition & {
  created: number;
  superseded: number;
  retired: number;
  removed: number;
};

export function sectionsForStage(
  definition: DwCustomDefinition | null | undefined,
  stageKey: string
): DwCustomSection[] {
  if (!definition) return [];
  return definition.sections
    .filter((section) => section.stageKey === stageKey)
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder || a.key.localeCompare(b.key))
    .map((section) =>
      section.retired && section.fields.some((field) => !field.retired)
        ? { ...section, fields: section.fields.map((field) => (field.retired ? field : { ...field, retired: true })) }
        : section
    );
}

export function customFieldsForStage(
  definition: DwCustomDefinition | null | undefined,
  stageKey: string
): DwCustomField[] {
  return customStageBlocks(definition, stageKey).flatMap((block) => block.fields);
}

export type DwCustomStageBlock = { section: DwCustomSection; fields: DwCustomField[] };

export function customStageBlocks(
  definition: DwCustomDefinition | null | undefined,
  stageKey: string
): DwCustomStageBlock[] {
  return sectionsForStage(definition, stageKey).map((section) => ({
    section,
    fields: section.fields.slice().sort((a, b) => a.sortOrder - b.sortOrder || a.key.localeCompare(b.key))
  }));
}

export function editableSections(sections: readonly DwCustomSection[]): DwCustomSection[] {
  return sections.filter((section) => !section.retired).map((section) => ({ ...section, fields: liveFields(section) }));
}

export function liveFields(section: DwCustomSection): DwCustomField[] {
  return section.fields
    .filter((field) => !field.retired)
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder || a.key.localeCompare(b.key));
}

export function retiredFields(section: DwCustomSection, values: DwEntryData): DwCustomField[] {
  return section.fields
    .filter((field) => field.retired && isFilled(values[field.key]))
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder || a.key.localeCompare(b.key));
}

export function answeredCustomKeys(
  fields: readonly DwCustomField[],
  ...buckets: ReadonlyArray<DwEntryData | null | undefined>
): Set<string> {
  const out = new Set<string>();
  for (const field of fields) {
    if (buckets.some((values) => values && isFilled(values[field.key]))) out.add(field.key);
  }
  return out;
}

export function customFieldToDwField(field: DwCustomField): DwField {
  return {
    key: field.key,
    label: field.label,
    type: isV1CustomType(field.type) ? field.type : unsupportedFieldType(field.type),
    tier: field.tier,
    required: field.required,
    help: field.help,
    unit: field.unit,
    options: field.options,
    maxLength: field.maxLength,
    ...(field.minValue === null ? {} : { minValue: field.minValue }),
    ...(field.maxValue === null ? {} : { maxValue: field.maxValue })
  };
}

export function customSectionEntityKey(section: DwCustomSection): string {
  return `${CUSTOM_ENTITY_KEY}:${section.key}`;
}

export function customSectionEntity(section: DwCustomSection): DwEntity {
  return {
    key: customSectionEntityKey(section),
    name: "DwCustomSection",
    cardinality: "SINGLETON",
    title: section.title,
    description: section.description,
    parent: "",
    labelField: "",
    fields: liveFields(section).map(customFieldToDwField)
  };
}

const cachedDefinitions = new Map<string, DwCustomDefinition>();
const definitionsInFlight = new Map<string, Promise<DwCustomDefinition>>();

export async function fetchCustomDefinition(
  workshopId: string,
  options?: { refresh?: boolean }
): Promise<DwCustomDefinition> {
  if (!options?.refresh) {
    const held = cachedDefinitions.get(workshopId);
    if (held) return held;
    const flight = definitionsInFlight.get(workshopId);
    if (flight) return flight;
  }

  const request = apiFetch<DwCustomDefinition>(
    `/design-workshops/${encodeURIComponent(workshopId)}/custom-sections`
  )
    .then((next) => {
      const previous = cachedDefinitions.get(workshopId);
      if (previous && previous.customSchemaVersion === next.customSchemaVersion) return previous;
      cachedDefinitions.set(workshopId, next);
      return next;
    })
    .finally(() => {
      definitionsInFlight.delete(workshopId);
    });

  definitionsInFlight.set(workshopId, request);
  return request;
}

export function adoptCustomDefinition(workshopId: string, definition: DwCustomDefinition): DwCustomDefinition {
  const held = cachedDefinitions.get(workshopId);
  if (held && held.customSchemaVersion === definition.customSchemaVersion) return held;
  cachedDefinitions.set(workshopId, definition);
  return definition;
}

export type DwCustomFieldBody = {
  key: string;
  label: string;
  type: string;
  tier: DwTier;
  required: boolean;
  help: string;
  unit: string;
  options: DwCustomOption[];
  maxLength: number;
  minValue: number | null;
  maxValue: number | null;
  sortOrder: number;
};

export type DwCustomSectionBody = {
  key: string;
  title: string;
  stageKey: string;
  description: string;
  sortOrder: number;
  fields: DwCustomFieldBody[];
};

export function fieldBody(field: DwCustomField, sortOrder: number): DwCustomFieldBody {
  return {
    key: field.key,
    label: field.label,
    type: field.type,
    tier: field.tier,
    required: field.required,
    help: field.help,
    unit: field.unit,
    options: field.options.map((option) => ({ value: option.value, label: option.label })),
    maxLength: field.maxLength,
    minValue: field.minValue,
    maxValue: field.maxValue,
    sortOrder
  };
}

export function definitionBody(
  sections: readonly DwCustomSection[],
  customSchemaVersion?: string | null
): { sections: DwCustomSectionBody[]; customSchemaVersion?: string } {
  return {
    ...(customSchemaVersion ? { customSchemaVersion } : {}),
    sections: sections
      .filter((section) => !section.retired)
      .map((section, index) => ({
        key: section.key,
        title: section.title,
        stageKey: section.stageKey,
        description: section.description,
        sortOrder: index,
        fields: section.fields
          .filter((field) => !field.retired)
          .map((field, fieldIndex) => fieldBody(field, fieldIndex))
      }))
  };
}

export async function saveCustomDefinition(
  workshopId: string,
  sections: readonly DwCustomSection[],
  customSchemaVersion?: string | null
): Promise<DwCustomSaveResult> {
  const result = await apiFetch<DwCustomSaveResult>(
    `/design-workshops/${encodeURIComponent(workshopId)}/custom-sections`,
    { method: "PUT", body: JSON.stringify(definitionBody(sections, customSchemaVersion)) }
  );
  cachedDefinitions.set(workshopId, {
    customSchemaVersion: result.customSchemaVersion,
    sections: result.sections,
    fetchedAt: result.fetchedAt
  });
  return result;
}

export function customSectionsProblem(error: unknown, fallback: string): string[] {
  if (error instanceof ApiError) {
    const body = error.payload;
    const detail =
      body && typeof body === "object" ? (body as { detail?: unknown }).detail : undefined;
    if (detail && typeof detail === "object" && !Array.isArray(detail)) {
      const record = detail as { message?: unknown; problems?: unknown };
      const problems = Array.isArray(record.problems)
        ? record.problems.filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
        : [];
      if (problems.length) return problems;
      if (typeof record.message === "string" && record.message.trim()) return [record.message];
    }
    if (error.message.trim()) return [error.message];
  }
  if (isUnreachable(error)) {
    return [
      "Custom questions can't be changed without a connection. Nothing was saved and nothing will be " +
        "sent later. Reconnect and save again; what you have typed here is kept."
    ];
  }
  return [error instanceof Error && error.message.trim() ? error.message : fallback];
}

export type CustomFieldEditCost =
  | { kind: "NEW" }
  | { kind: "FREE" }
  | { kind: "SUPERSEDE"; was: string }
  | { kind: "RETIRE" }
  | { kind: "DELETE" };

export function fieldEditCost(
  stored: DwCustomField | undefined,
  next: { label: string } | null,
  answered: boolean
): CustomFieldEditCost {
  if (!stored) return { kind: "NEW" };
  if (!next) return answered ? { kind: "RETIRE" } : { kind: "DELETE" };
  const rewritten = stored.label.trim() !== next.label.trim();
  if (rewritten && answered) return { kind: "SUPERSEDE", was: stored.label };
  return { kind: "FREE" };
}

export function customDefinitionProblems(
  sections: readonly DwCustomSection[],
  stages: readonly DwStage[] = []
): string[] {
  const problems: string[] = [];

  if (sections.length > MAX_CUSTOM_SECTIONS) {
    problems.push(
      `A workshop may carry at most ${MAX_CUSTOM_SECTIONS} custom sections and this one names ` +
        `${sections.length}. Merge two of them, or move the questions that belong to a different ` +
        "stage into a section on that stage."
    );
  }

  const seenSectionKeys = new Set<string>();
  const seenFieldKeys = new Map<string, string>();
  const seenLabels = new Map<string, string>();

  for (const section of sections) {
    const where = section.key || "(unnamed)";
    if (!CUSTOM_KEY_PATTERN.test(section.key || "")) {
      problems.push(
        `Section key ${JSON.stringify(section.key)} cannot be used. A key must start with a ` +
          `lower-case letter, carry only letters and digits after it, and be at most ` +
          `${MAX_CUSTOM_KEY_CHARS} characters — it is what the answers are stored under and it is ` +
          "never shown to anybody."
      );
    } else if (seenSectionKeys.has(section.key)) {
      problems.push(
        `Two sections are both keyed ${JSON.stringify(section.key)}. A section key is unique ` +
          "within the workshop; rename one of them."
      );
    } else {
      seenSectionKeys.add(section.key);
    }

    if (!section.title.trim()) {
      problems.push(`Section ${where} has no title. Give it the heading it should print under.`);
    } else if (section.title.length > MAX_CUSTOM_TITLE_CHARS) {
      problems.push(`Section ${where}'s title is longer than ${MAX_CUSTOM_TITLE_CHARS} characters.`);
    }
    if (section.description.length > MAX_CUSTOM_DESCRIPTION_CHARS) {
      problems.push(
        `Section ${where}'s description is longer than ${MAX_CUSTOM_DESCRIPTION_CHARS} characters.`
      );
    }
    if (!section.stageKey) {
      problems.push(
        `Section ${where} does not say which stage its questions are asked at. That is where the ` +
          "answers are stored and where they are counted towards the stage's completeness. If the " +
          "section should print at the back of the report instead, it still belongs to the stage it " +
          "is ASKED at."
      );
    }

    const fields = liveFields(section);
    if (fields.length > MAX_CUSTOM_FIELDS_PER_SECTION) {
      problems.push(
        `Section ${where} declares ${fields.length} fields; the limit is ` +
          `${MAX_CUSTOM_FIELDS_PER_SECTION}. Split it into two sections.`
      );
    }

    const stage = section.stageKey ? stages.find((candidate) => candidate.key === section.stageKey) : undefined;

    for (const field of fields) {
      problems.push(...fieldProblems(section, field, seenFieldKeys, seenLabels, stage));
    }
  }

  return problems;
}

function fieldProblems(
  section: DwCustomSection,
  field: DwCustomField,
  seenFieldKeys: Map<string, string>,
  seenLabels: Map<string, string>,
  stage?: DwStage
): string[] {
  const problems: string[] = [];
  const where = `${section.key}.${field.key || "(unnamed)"}`;

  if (!CUSTOM_KEY_PATTERN.test(field.key || "")) {
    problems.push(
      `Field key ${JSON.stringify(field.key)} in section ${JSON.stringify(section.key)} cannot be ` +
        "used. A key must start with a lower-case letter, carry only letters and digits after it, " +
        `and be at most ${MAX_CUSTOM_KEY_CHARS} characters. It is what the answer is stored under ` +
        "and it can never be renamed — renaming one orphans the answers already given under it."
    );
  } else if (seenFieldKeys.has(field.key)) {
    problems.push(
      `Field key ${JSON.stringify(field.key)} is used twice (${seenFieldKeys.get(field.key)} and ` +
        `${where}). A field key is unique across the whole workshop, because the answers for one ` +
        "stage are all stored side by side and two fields sharing a key would share one answer."
    );
  } else {
    seenFieldKeys.set(field.key, where);
  }

  if (!field.label.trim()) {
    problems.push(`Field ${where} has no label. The label is the question the designer reads.`);
  } else if (field.label.length > MAX_CUSTOM_LABEL_CHARS) {
    problems.push(
      `Field ${where}'s label is longer than ${MAX_CUSTOM_LABEL_CHARS} characters. A label is a ` +
        "question, not a paragraph — put the explanation in the help text."
    );
  }
  if (field.help.length > MAX_CUSTOM_HELP_CHARS) {
    problems.push(`Field ${where}'s help text is longer than ${MAX_CUSTOM_HELP_CHARS} characters.`);
  }
  if (field.unit.length > MAX_CUSTOM_UNIT_CHARS) {
    problems.push(`Field ${where}'s unit is longer than ${MAX_CUSTOM_UNIT_CHARS} characters.`);
  }

  if (!isV1CustomType(field.type)) {
    problems.push(
      `Field ${where}: a custom question can't be of type ${field.type}. Choose one of: ` +
        `${V1_CUSTOM_TYPES.slice().sort().join(", ")}.`
    );
  }

  if (field.required && field.tier !== "BASIC") {
    problems.push(
      `Field ${where} is required but its tier is ${field.tier}. Only a Basic question may be ` +
        "required — the tiers exist so a workshop held somewhere without power or a specialist can " +
        "still be completed, and a required Standard question makes that impossible."
    );
  }

  if (OPTION_TYPES.has(field.type)) {
    if (field.options.length < 2) {
      problems.push(
        `Field ${where} is a ${field.type} with ${countNoun(field.options.length, "option")}. A choice needs ` +
          "at least two; a single-option list is a label."
      );
    }
    if (field.options.length > MAX_CUSTOM_OPTIONS) {
      problems.push(`Field ${where} declares ${field.options.length} options; the limit is ${MAX_CUSTOM_OPTIONS}.`);
    }
    const seenValues = new Set<string>();
    for (const option of field.options) {
      const token = option.value.trim();
      if (!token) problems.push(`Field ${where} has an option with no value.`);
      else if (seenValues.has(token)) {
        problems.push(
          `Field ${where} lists the option ${JSON.stringify(token)} twice. Two options with one ` +
            "value are one answer under two labels."
        );
      }
      if (token.length > MAX_CUSTOM_OPTION_VALUE_CHARS) {
        problems.push(
          `Field ${where}'s option value ${JSON.stringify(token)} is longer than ` +
            `${MAX_CUSTOM_OPTION_VALUE_CHARS} characters. The option value is the code the answer is ` +
            "saved as and is not shown to anyone — shorten it, and put the words after the “|”, " +
            "where they are printed."
        );
      }
      const printed = option.label.trim();
      if (printed.length > MAX_CUSTOM_LABEL_CHARS) {
        problems.push(
          `Field ${where}'s printed form for ${JSON.stringify(token || option.value)} is longer than ` +
            `${MAX_CUSTOM_LABEL_CHARS} characters. That is a whole question's worth of words on one ` +
            "line of a dropdown; put the explanation in the help text."
        );
      }
      seenValues.add(token);
    }
  } else if (field.options.length) {
    problems.push(
      `Field ${where} is a ${field.type} and cannot carry options. Remove them, or make it a choice.`
    );
  }

  if (field.minValue !== null && field.maxValue !== null && field.minValue > field.maxValue) {
    problems.push(
      `Field ${where} has a smallest value (${field.minValue}) above its largest (${field.maxValue}).`
    );
  }
  if ((field.minValue !== null || field.maxValue !== null) && !BOUNDED_TYPES.has(field.type)) {
    problems.push(
      `Field ${where} is a ${field.type} and its smallest/largest value would never be checked. ` +
        "Remove the bounds, or make the question a number."
    );
  }
  if (field.maxLength && !LENGTH_TYPES.has(field.type)) {
    problems.push(
      `Field ${where} is a ${field.type} and its maximum length would never be checked. Remove it, ` +
        "or make the question text."
    );
  }

  const label = field.label.trim().toLowerCase();
  if (label) {
    const id = JSON.stringify([section.stageKey, label]);
    if (seenLabels.has(id)) {
      problems.push(
        `Field ${where} and ${seenLabels.get(id)} are both called ${JSON.stringify(field.label)} on ` +
          "the same stage. Two questions with one name become one line on the readiness screen " +
          "while the count beside it says two."
      );
    } else {
      seenLabels.set(id, where);
    }
  }

  if (stage) {
    for (const entity of stage.entities) {
      const core = entity.fields.find((candidate) => candidate.key === field.key && !candidate.deprecated);
      if (core) {
        problems.push(
          `${JSON.stringify(field.key)} is already a field of stage ${stage.number} (${stage.title} → ` +
            `${entity.title}: ${core.label}). Choose another key.`
        );
      }
    }

    const singleton = stage.entities.find((entity) => entity.cardinality === "SINGLETON");
    if (singleton && label) {
      const clash = singleton.fields.find(
        (candidate) => !candidate.deprecated && candidate.label.trim().toLowerCase() === label
      );
      if (clash) {
        problems.push(
          `A field on stage ${stage.number} (${stage.title}) is already called ` +
            `${JSON.stringify(clash.label)}. Two questions with one name become one line on the readiness ` +
            "screen and in the report's Outstanding column, while the count beside it still says two — so " +
            "the report disagrees with itself. Give this one a different label."
        );
      }
    }
  }

  return problems;
}

export type CustomDefinitionDiff = {
  created: number;
  superseded: Array<{ was: string; now: string }>;
  retiredFields: string[];
  deletedFields: string[];
  retiredSections: string[];
  deletedSections: string[];
  movedSections: string[];
  retypedFields: Array<{ label: string; was: string; now: string }>;
};

export function diffCustomDefinition(
  stored: readonly DwCustomSection[],
  next: readonly DwCustomSection[],
  answered: Readonly<Record<string, ReadonlySet<string>>>
): CustomDefinitionDiff {
  const diff: CustomDefinitionDiff = {
    created: 0,
    superseded: [],
    retiredFields: [],
    deletedFields: [],
    retiredSections: [],
    deletedSections: [],
    movedSections: [],
    retypedFields: []
  };
  const storedByKey = new Map(stored.map((section) => [section.key, section]));
  const nextKeys = new Set(next.map((section) => section.key));

  for (const section of next) {
    const previous = storedByKey.get(section.key);
    const answeredHere = answered[previous?.stageKey ?? section.stageKey] ?? new Set<string>();
    if (!previous) {
      diff.created += liveFields(section).length;
      continue;
    }
    if (previous.stageKey !== section.stageKey) {
      if (sectionHoldsAnswer(previous, answeredHere)) diff.movedSections.push(previous.title);
    }
    const keptKeys = new Set(liveFields(section).map((field) => field.key));
    for (const field of liveFields(section)) {
      const before = previous.fields.find((candidate) => candidate.key === field.key && !candidate.retired);
      if (!before) {
        diff.created += 1;
        continue;
      }
      const isAnswered = answeredHere.has(field.key);
      const cost = fieldEditCost(before, { label: field.label }, isAnswered);
      if (cost.kind === "SUPERSEDE") diff.superseded.push({ was: cost.was, now: field.label });
      else if (isAnswered && before.type !== field.type) {
        diff.retypedFields.push({ label: field.label, was: before.type, now: field.type });
      }
    }
    for (const before of previous.fields) {
      if (before.retired || keptKeys.has(before.key)) continue;
      if (answeredHere.has(before.key)) diff.retiredFields.push(before.label);
      else diff.deletedFields.push(before.label);
    }
  }

  for (const previous of stored) {
    if (nextKeys.has(previous.key) || previous.retired) continue;
    const answeredHere = answered[previous.stageKey] ?? new Set<string>();
    if (sectionHoldsAnswer(previous, answeredHere)) {
      diff.retiredSections.push(previous.title);
    } else {
      diff.deletedSections.push(previous.title);
    }
  }

  return diff;
}

function sectionHoldsAnswer(section: DwCustomSection, answered: ReadonlySet<string>): boolean {
  return section.fields.some((field) => answered.has(field.key));
}

export function storedSectionHoldsAnswer(
  stored: readonly DwCustomSection[],
  sectionKey: string,
  answered: Readonly<Record<string, ReadonlySet<string>>>
): boolean {
  const previous = stored.find((candidate) => candidate.key === sectionKey);
  if (!previous) return false;
  return sectionHoldsAnswer(previous, answered[previous.stageKey] ?? new Set<string>());
}

export function diffIsEmpty(diff: CustomDefinitionDiff): boolean {
  return (
    diff.created === 0 &&
    !diff.superseded.length &&
    !diff.retiredFields.length &&
    !diff.deletedFields.length &&
    !diff.retiredSections.length &&
    !diff.deletedSections.length &&
    !diff.retypedFields.length
  );
}

export function suggestCustomKey(label: string, taken: ReadonlySet<string>): string {
  const words = label
    .toLowerCase()
    .replace(/[^a-z0-9\s]+/g, " ")
    .split(/\s+/)
    .filter(Boolean);
  let stem = words
    .map((word, index) => (index === 0 ? word : word[0].toUpperCase() + word.slice(1)))
    .join("")
    .slice(0, MAX_CUSTOM_KEY_CHARS);
  if (!/^[a-z]/.test(stem)) stem = `q${stem}`.slice(0, MAX_CUSTOM_KEY_CHARS);
  if (!CUSTOM_KEY_PATTERN.test(stem)) stem = "question";
  if (!taken.has(stem)) return stem;
  for (let n = 2; n < 1000; n += 1) {
    const candidate = `${stem.slice(0, MAX_CUSTOM_KEY_CHARS - 3)}${n}`;
    if (!taken.has(candidate)) return candidate;
  }
  return stem;
}

export function blankCustomField(overrides?: Partial<DwCustomField>): DwCustomField {
  return {
    id: "",
    key: "",
    label: "",
    type: "TEXT",
    tier: "STANDARD",
    required: false,
    help: "",
    unit: "",
    options: [],
    maxLength: 0,
    minValue: null,
    maxValue: null,
    sortOrder: 0,
    retired: false,
    supersededById: null,
    ...overrides
  };
}

export function blankCustomSection(overrides?: Partial<DwCustomSection>): DwCustomSection {
  return {
    id: "",
    key: "",
    stageKey: "",
    title: "",
    description: "",
    sortOrder: 0,
    revision: 1,
    retired: false,
    fields: [],
    ...overrides
  };
}

export function customAnswerText(field: DwCustomField, value: DwValue | undefined): string {
  const label = (token: string) =>
    field.options.find((option) => option.value === token)?.label.trim() || token;
  if (value === null || value === undefined) return "";
  if (Array.isArray(value)) return value.map((item) => label(String(item))).join(", ");
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "object") return "";
  return label(String(value));
}
