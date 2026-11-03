import { byCount, countNoun } from "@/lib/format";
import {
  isFilled,
  rowTitle,
  stageProgress,
  type DwEntity,
  type DwField,
  type DwRegistry,
  type DwRow,
  type DwStage,
  type DwStageCompleteness
} from "@/lib/designWorkshops";
import {
  customSectionEntityKey,
  customStageBlocks,
  type DwCustomStageBlock
} from "@/lib/customSections";
import { localCompleteness, stageDataOf, type DwDraft, type DwDraftStage } from "@/lib/designWorkshopStore";
import { stageFieldHref } from "@/lib/workshopSearch";

const REPORT_STAGE_KEY = "REPORT_GENERATION";
const REPORT_SETTINGS_ENTITY = "reportSettings";
const TEMPLATE_FIELD_KEY = "templateId";
const EXCLUDED_STAGES_FIELD_KEY = "excludedStages";

export type ReadinessAddress = {
  entityKey: string;
  entityTitle: string;
  fieldKey: string;
  fieldLabel: string;
  anchorFieldKey: string;
  rowKey: string | null;
  rowTitle: string | null;
  occurrences: number;
  rowCount: number;
};

export type ReadinessItem = {
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  label: string;
  address: ReadinessAddress | null;
  href: string;
};

export type ReadinessCheck = {
  id: string;
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  title: string;
  detail: string;
  href: string;
};

export type ReadinessStageGap = {
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  optionalTotal: number;
  optionalFilled: number;
  href: string;
};

export type WorkshopReadiness = {
  stagesTotal: number;
  stagesComplete: number;
  requiredTotal: number;
  requiredFilled: number;
  blockedStages: number;
  blocking: ReadinessItem[];
  checks: ReadinessCheck[];
  advisory: ReadinessStageGap[];
  isSubmittable: boolean;
};

function rowKeyOf(row: DwRow): string | null {
  if (typeof row._clientKey === "string" && row._clientKey) return row._clientKey;
  if (typeof row._entryId === "string" && row._entryId) return row._entryId;
  return null;
}

function missingLabel(entity: DwEntity, field: DwField): string {
  return entity.cardinality === "SINGLETON" ? field.label : `${entity.title}: ${field.label}`;
}

function stageAddresses(
  spec: DwStage,
  stage: DwDraftStage | undefined,
  customBlocks: readonly DwCustomStageBlock[] = []
): Map<string, ReadinessAddress> {
  const data = stageDataOf(stage);
  const found = new Map<string, ReadinessAddress>();

  const note = (label: string, address: ReadinessAddress) => {
    const seen = found.get(label);
    if (seen) seen.occurrences += 1;
    else found.set(label, address);
  };

  const values = stage?.custom ?? {};
  for (const block of customBlocks) {
    for (const field of block.fields) {
      if (field.retired || !field.required) continue;
      if (isFilled(values[field.key])) continue;
      note(field.label, {
        entityKey: customSectionEntityKey(block.section),
        entityTitle: block.section.title,
        fieldKey: field.key,
        fieldLabel: field.label,
        anchorFieldKey: field.key,
        rowKey: null,
        rowTitle: null,
        occurrences: 1,
        rowCount: 1
      });
    }
  }

  for (const entity of spec.entities) {
    if (entity.cardinality === "SINGLETON") {
      for (const field of entity.fields) {
        if (field.deprecated || !field.required) continue;
        if (isFilled(data.singleton[field.key])) continue;
        note(missingLabel(entity, field), {
          entityKey: entity.key,
          entityTitle: entity.title,
          fieldKey: field.key,
          fieldLabel: field.label,
          anchorFieldKey: field.captionFor || field.key,
          rowKey: null,
          rowTitle: null,
          occurrences: 1,
          rowCount: 1
        });
      }
      continue;
    }

    const rows = data.collections[entity.key] ?? [];
    rows.forEach((row, index) => {
      for (const field of entity.fields) {
        if (field.deprecated || !field.required) continue;
        if (isFilled(row[field.key])) continue;
        note(missingLabel(entity, field), {
          entityKey: entity.key,
          entityTitle: entity.title,
          fieldKey: field.key,
          fieldLabel: field.label,
          anchorFieldKey: field.captionFor || field.key,
          rowKey: rowKeyOf(row),
          rowTitle: rowTitle(entity, row, index),
          occurrences: 1,
          rowCount: rows.length
        });
      }
    });
  }

  return found;
}

function reportChecks(
  registry: DwRegistry,
  draft: DwDraft,
  workshopId: string,
  scores: Record<string, DwStageCompleteness>
): ReadinessCheck[] {
  const spec = registry.stages.find((stage) => stage.key === REPORT_STAGE_KEY);
  if (!spec) return [];
  const entity = spec.entities.find((candidate) => candidate.key === REPORT_SETTINGS_ENTITY);
  if (!entity) return [];

  const settings = stageDataOf(draft.stages[spec.key]).singleton;
  const out: ReadinessCheck[] = [];
  const at = (fieldKey: string) => stageFieldHref(workshopId, spec.key, entity.key, fieldKey);

  const templateField = entity.fields.find((field) => field.key === TEMPLATE_FIELD_KEY);
  const chosen = settings[TEMPLATE_FIELD_KEY];
  if (templateField && typeof chosen === "string" && chosen.trim()) {
    const options = templateField.options ?? registry.enums[templateField.enum ?? ""] ?? [];
    if (options.length && !options.some((option) => option.value === chosen.trim())) {
      out.push({
        id: "template-unknown",
        stageKey: spec.key,
        stageNumber: spec.number,
        stageTitle: spec.title,
        title: "The report template chosen in stage 20 is not available",
        detail:
          `Stage 20 names "${chosen.trim()}", which is not in the list of templates available here. The ` +
          "report will be generated from the workshop's own template instead, and the file will not " +
          "match the format named on the form. Re-pick the template to settle it.",
        href: at(TEMPLATE_FIELD_KEY)
      });
    }
  }

  const excluded = settings[EXCLUDED_STAGES_FIELD_KEY];
  const excludedKeys = Array.isArray(excluded) ? excluded.filter((key): key is string => typeof key === "string") : [];
  for (const stageKey of excludedKeys) {
    const target = registry.stages.find((candidate) => candidate.key === stageKey);
    const score = target ? scores[target.key] : undefined;
    if (!target || !score) continue;
    if (score.requiredFilled === 0 && score.optionalFilled === 0) continue;
    out.push({
      id: `stage-excluded:${target.key}`,
      stageKey: target.key,
      stageNumber: target.number,
      stageTitle: target.title,
      title: `Stage ${target.number} is excluded from the report but has answers in it`,
      detail:
        `${countNoun(score.requiredFilled + score.optionalFilled, "field")} ` +
          `${byCount(score.requiredFilled + score.optionalFilled, "is", "are")} filled in on "${target.title}", and ` +
        "stage 20 leaves this stage out of the generated report. That is a legitimate choice — but the " +
        "work will not appear in the file that is submitted.",
      href: at(EXCLUDED_STAGES_FIELD_KEY)
    });
  }

  return out;
}

export function workshopReadiness(registry: DwRegistry, draft: DwDraft, workshopId: string): WorkshopReadiness {
  const scores = localCompleteness(registry, draft);

  const blocking: ReadinessItem[] = [];
  const advisory: ReadinessStageGap[] = [];
  let requiredTotal = 0;
  let requiredFilled = 0;
  let blockedStages = 0;

  for (const spec of registry.stages) {
    const score = scores[spec.key];
    if (!score) continue;

    requiredTotal += score.requiredTotal;
    requiredFilled += score.requiredFilled;

    if (score.missing.length) {
      blockedStages += 1;
      const addresses = stageAddresses(
        spec,
        draft.stages[spec.key],
        customStageBlocks(draft.customDefinition ?? null, spec.key)
      );
      for (const label of score.missing) {
        const address = addresses.get(label) ?? null;
        blocking.push({
          stageKey: spec.key,
          stageNumber: spec.number,
          stageTitle: spec.title,
          label,
          address,
          href: address
            ? stageFieldHref(workshopId, spec.key, address.entityKey, address.anchorFieldKey, address.rowKey)
            : `/design-workshops/${workshopId}/stages/${spec.key}`
        });
      }
    }

    if (score.optionalFilled < score.optionalTotal) {
      advisory.push({
        stageKey: spec.key,
        stageNumber: spec.number,
        stageTitle: spec.title,
        optionalTotal: score.optionalTotal,
        optionalFilled: score.optionalFilled,
        href: `/design-workshops/${workshopId}/stages/${spec.key}`
      });
    }
  }

  const { stagesComplete } = stageProgress(
    registry.stages.map((spec) => scores[spec.key]).filter((score): score is DwStageCompleteness => !!score),
    registry.stages.length
  );

  return {
    stagesTotal: registry.stages.length,
    stagesComplete,
    requiredTotal,
    requiredFilled,
    blockedStages,
    blocking,
    checks: reportChecks(registry, draft, workshopId, scores),
    advisory,
    isSubmittable: blocking.length === 0
  };
}

export function readinessSummary(readiness: WorkshopReadiness): string {
  const { stagesComplete, stagesTotal, blocking, blockedStages } = readiness;
  const stages = `${stagesComplete} of ${stagesTotal} stages complete`;
  if (!blocking.length) return `${stages}; no required fields remain.`;
  const fields = `${blocking.length} required field${blocking.length === 1 ? "" : "s"}`;
  const where = `${blockedStages} stage${blockedStages === 1 ? "" : "s"}`;
  return `${stages}; ${fields} remain${blocking.length === 1 ? "s" : ""} in ${where}.`;
}

export function itemFieldName(item: ReadinessItem): string {
  const address = item.address;
  if (!address) return item.label;
  if (!address.rowTitle) return address.fieldLabel;
  return `${address.fieldLabel} · ${address.rowTitle}`;
}
