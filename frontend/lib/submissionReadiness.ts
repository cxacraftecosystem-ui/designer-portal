/**
 * What still stops this workshop being submitted, assembled into one ranked, actionable list.
 *
 * WHY THIS FILE IS ASSEMBLY AND NOT ARITHMETIC. Completeness is already computed, twice and
 * deliberately: `stage_completeness` on the server and `scoreStageData` here, which are
 * character-for-character ports of each other so a stage cannot read "complete" on a phone and 422
 * on a submit. A THIRD scorer written to drive a checklist would be a third opinion, and the day it
 * disagreed the designer would be told to go and fill in a field the Save button was perfectly happy
 * with. So the set of blocking items in this module is `DwStageCompleteness.missing` and nothing
 * else — the very list the report already prints in `X-Report-Warnings` as "Stage 7 (…): 3 required
 * field(s) not recorded". This module adds two things to it and only two: an ADDRESS for each entry,
 * and an order.
 *
 * WHY AN ADDRESS HAS TO BE DERIVED SEPARATELY. `missing` holds field LABELS, because that is what a
 * warning sentence and a report annexure need. A label cannot be navigated to. {@link stageAddresses}
 * therefore walks the registry a second time to work out WHERE each label lives — but it decides
 * nothing about completeness: it is a lookup keyed by the label the scorer already produced, and an
 * entry it fails to resolve degrades to a link to the stage rather than dropping the item. If the two
 * walks ever drift, the worst case is a link that lands on the stage instead of on the box; it can
 * never be a blocker that appears or vanishes. That asymmetry is the whole design.
 *
 * WHY IT IS PURE. No React, no fetch, no IndexedDB — it takes the registry and the local draft and
 * returns data, in the shape of `services/market_analysis.py` and `lib/marketAnalysis.ts`. That is
 * what lets the screen answer "what is left?" on a laptop that has had no signal for four days, which
 * is the only place the question is ever actually asked: nobody chases a submission from a desk with
 * a connection, they chase it in a courtyard on the last afternoon of the workshop.
 *
 * WHAT IS DELIBERATELY NOT HERE. Standard- and Advanced-tier gaps are reported as COUNTS PER STAGE
 * and never as items beside the blocking ones. They do not 422 a submit and they do not produce a
 * report warning; mixing them in would turn a list of four real obstacles into a list of two hundred
 * suggestions, and a designer who learns that most of this screen is optional stops reading the part
 * that is not.
 */

import {
  isFilled,
  rowTitle,
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

/* ────────────────────────────────────────────────────────────────────────────
 * Stage 20's settings, by key
 *
 * Named rather than inlined because all three are read by the server too — `resolve_template_id`
 * and `apply_report_settings` — and a typo here would produce a check that silently never fires,
 * which is indistinguishable on screen from a workshop that has nothing wrong with it.
 * ──────────────────────────────────────────────────────────────────────────── */

const REPORT_STAGE_KEY = "REPORT_GENERATION";
const REPORT_SETTINGS_ENTITY = "reportSettings";
const TEMPLATE_FIELD_KEY = "templateId";
const EXCLUDED_STAGES_FIELD_KEY = "excludedStages";

/* ────────────────────────────────────────────────────────────────────────────
 * Shapes
 * ──────────────────────────────────────────────────────────────────────────── */

/** Where an outstanding field actually is, so the item can be clicked rather than hunted for. */
export type ReadinessAddress = {
  entityKey: string;
  entityTitle: string;
  fieldKey: string;
  fieldLabel: string;
  /**
   * The field the stage page should scroll to, which is `fieldKey` except for a caption — a caption
   * input is drawn under the media field it describes and has no wrapper of its own to land on.
   */
  anchorFieldKey: string;
  /** The row's `_clientKey` (or its server `_entryId`), so the right row of a collection opens. */
  rowKey: string | null;
  /** "Sketch 3", or the row's own title. Null for a singleton, which has no row to name. */
  rowTitle: string | null;
  /**
   * How many rows of this collection are missing this same field.
   *
   * `missing` is de-duplicated by label, exactly as the server de-duplicates it, so eleven prototype
   * rows with no material named are ONE entry. Printing "in 11 of 18 rows" is the difference between
   * an item a designer can size up and one that reads as a single forgotten box.
   */
  occurrences: number;
  /** How many rows of this collection exist at all, so `occurrences` has a denominator. */
  rowCount: number;
};

/** One thing that will 422 a submit, with somewhere to click. */
export type ReadinessItem = {
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  /** Exactly the string `stage_completeness` puts in `missing` — the report's own words, verbatim. */
  label: string;
  /** Null only if the address walk could not place the label; the item still stands and still links. */
  address: ReadinessAddress | null;
  href: string;
};

/** A report-blocking condition that is not a missing field. */
export type ReadinessCheck = {
  id: string;
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  title: string;
  detail: string;
  href: string;
};

/** A stage's optional-tier shortfall. Informational, and never ranked beside a blocker. */
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
  /** How many stages still hold at least one unfilled required field. */
  blockedStages: number;
  /** Everything that 422s a submit, in stage order. */
  blocking: ReadinessItem[];
  /** Everything the report would warn about that is not a missing field. */
  checks: ReadinessCheck[];
  /** Optional-tier shortfalls, per stage, for the stages that have one. */
  advisory: ReadinessStageGap[];
  /**
   * Nothing here would refuse a submit.
   *
   * Deliberately NOT weakened by `checks`: a report generated from a substituted template is a report,
   * and telling a designer they may not submit over something the server would happily accept is the
   * kind of invented gate this repo has no business adding. The checks are shown; they do not block.
   */
  isSubmittable: boolean;
};

/* ────────────────────────────────────────────────────────────────────────────
 * Addresses
 * ──────────────────────────────────────────────────────────────────────────── */

/** A row's stable identity, in the order the stage page will recognise it. */
function rowKeyOf(row: DwRow): string | null {
  if (typeof row._clientKey === "string" && row._clientKey) return row._clientKey;
  if (typeof row._entryId === "string" && row._entryId) return row._entryId;
  return null;
}

/** The label `scoreStageData` would file this field's absence under. Must match it exactly. */
function missingLabel(entity: DwEntity, field: DwField): string {
  return entity.cardinality === "SINGLETON" ? field.label : `${entity.title}: ${field.label}`;
}

/**
 * Where every unfilled required field of one stage lives, keyed by the label the scorer files it
 * under.
 *
 * Reads values through the SAME two primitives the scorer does — `stageDataOf` to flatten the
 * singletons and `isFilled` to judge a value — so that "which fields are unfilled" cannot be
 * answered differently here. What this function owns is only the mapping from a label to a place.
 *
 * The FIRST offending row keeps the link. A designer sent to the first prototype missing its material
 * can work down the list from there; sending them to the last one would have them scroll back up.
 */
function stageAddresses(
  spec: DwStage,
  stage: DwDraftStage | undefined,
  /**
   * This stage's designer-defined questions, still grouped by the section that asks them.
   *
   * PASSED IN RATHER THAN RESOLVED HERE, so this module keeps the property its header claims: it takes
   * the registry and the draft and decides nothing about completeness. The caller already holds the
   * draft the definition lives on. Grouped rather than flat because an address needs the section's
   * rendering identity — see {@link customSectionEntityKey}.
   */
  customBlocks: readonly DwCustomStageBlock[] = []
): Map<string, ReadinessAddress> {
  const data = stageDataOf(stage);
  const found = new Map<string, ReadinessAddress>();

  const note = (label: string, address: ReadinessAddress) => {
    const seen = found.get(label);
    // Second and later rows only raise the count: the address is the first one, on purpose.
    if (seen) seen.occurrences += 1;
    else found.set(label, address);
  };

  /*
    THE DESIGNER'S OWN QUESTIONS GET ADDRESSES TOO, AND THE COST OF OMITTING THEM IS A HIGHLIGHT RATHER
    THAN AN ITEM. That asymmetry is this module's whole design and it is worth restating where a new
    kind of field arrives: the BLOCKING SET is `DwStageCompleteness.missing` and nothing else, so a
    required custom question appears on this screen the moment the scorer counts it, with or without
    this walk. What the walk adds is somewhere to click — and a label it cannot place degrades to a
    link to the stage, never to a dropped blocker.

    NOTED FIRST, BEFORE THE ENTITIES, for the same reason the scorer counts them between the singleton
    and the collections: `note` keeps the FIRST address it is given for a label, so if a custom question
    and a collection column ever shared a bare label the first one noted wins the link. The scorer files
    a collection field's label with its entity title prefixed, so the two cannot actually collide — this
    ordering is what keeps that true if either rule is ever changed.

    THE KEY IS THE BARE LABEL, matching `scoreStageData` exactly. `missingLabel` below is not used here
    because it takes an entity, and a custom question has none: its label is filed bare, like a
    singleton field's.
  */
  const values = stage?.custom ?? {};
  for (const block of customBlocks) {
    for (const field of block.fields) {
      if (field.retired || !field.required) continue;
      if (isFilled(values[field.key])) continue;
      note(field.label, {
        entityKey: customSectionEntityKey(block.section),
        // The section's own heading, which is what the designer will see above the box when they get
        // there. Not a phrase invented here: an item that names a place by a different word from the
        // one on the destination screen costs the designer a second search once they arrive.
        entityTitle: block.section.title,
        fieldKey: field.key,
        fieldLabel: field.label,
        // No caption fields exist among the twelve v1 types, so the anchor is always the field itself.
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

/* ────────────────────────────────────────────────────────────────────────────
 * The report's own checks, beyond the fields
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The stage-20 conditions that change the delivered document without failing anything.
 *
 * Both are computable from the registry and the draft alone, which is the bar for putting a check
 * here at all: a warning that needs a connection to be raised is a warning a designer in a village
 * never sees. The transcript-annexure warning (`annexure_warnings`) deliberately has no counterpart
 * here — whether a recording has been transcribed is a fact that lives on the server's media queue
 * and cannot be known offline, and guessing at it would be exactly the fabricated confidence the
 * repository refuses elsewhere.
 */
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

  /*
    THE TEMPLATE PICKER, AGAINST THE TEMPLATES THAT EXIST. `resolve_template_id` ignores a token it
    does not recognise rather than obeying it, and the builder then reports a substitution — so a
    stage-20 answer left over from a build that offered a template this one does not produces a
    different document from the one named on the form, and says so only in a header beside the
    download. The field is required and Basic, so the EMPTY case is already a blocking item above;
    this catches the case where it is filled in and inert.
  */
  const templateField = entity.fields.find((field) => field.key === TEMPLATE_FIELD_KEY);
  const chosen = settings[TEMPLATE_FIELD_KEY];
  if (templateField && typeof chosen === "string" && chosen.trim()) {
    const options = templateField.options ?? registry.enums[templateField.enum ?? ""] ?? [];
    // An empty option list means the registry did not inline them and holds no matching enum — a
    // shape this build cannot judge. Saying nothing is the honest answer; asserting the template is
    // unknown on the strength of a list we failed to read would send a designer to change a correct
    // answer.
    if (options.length && !options.some((option) => option.value === chosen.trim())) {
      out.push({
        id: "template-unknown",
        stageKey: spec.key,
        stageNumber: spec.number,
        stageTitle: spec.title,
        title: "The report template chosen in stage 20 is not one this repository offers",
        detail:
          `Stage 20 names "${chosen.trim()}", which is not in the list of templates available here. The ` +
          "report will be generated from the workshop's own template instead, and the file will not " +
          "match the format named on the form. Re-pick the template to settle it.",
        href: at(TEMPLATE_FIELD_KEY)
      });
    }
  }

  /*
    STAGES DELIBERATELY LEFT OUT — but only where leaving one out actually loses something. Excluding
    an empty stage is what the setting is FOR, and warning about it would train a designer to ignore
    this section. Whether a stage holds anything is read off the score that is already computed, so
    this costs one lookup and no traversal.
  */
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
        `${score.requiredFilled + score.optionalFilled} field(s) are filled in on "${target.title}", and ` +
        "stage 20 leaves this stage out of the generated report. That is a legitimate choice — but the " +
        "work will not appear in the file that is submitted.",
      href: at(EXCLUDED_STAGES_FIELD_KEY)
    });
  }

  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The assembly
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Everything standing between this draft and a submission.
 *
 * `workshopId` is what the links are built against and is the caller's to choose: a workshop created
 * offline is reached by its local id and a synced one by the server's, and this module must not have
 * an opinion about which — getting it wrong produces a whole screen of links to a workshop that does
 * not exist under that name.
 *
 * ORDERED BY STAGE NUMBER, not by severity within the blocking set. Every entry in that set blocks
 * equally (they are all the same 422), so the only ordering left that means anything is the one the
 * designer already has in their head from the 22-stage index they arrived from. A list sorted by
 * "most missing first" would put stage 13 above stage 1 and cost a re-read on every visit.
 */
export function workshopReadiness(registry: DwRegistry, draft: DwDraft, workshopId: string): WorkshopReadiness {
  // THE SINGLE AUTHORITY. Everything below counts, addresses and orders what this returned; nothing
  // below decides whether a field is filled.
  const scores = localCompleteness(registry, draft);

  const blocking: ReadinessItem[] = [];
  const advisory: ReadinessStageGap[] = [];
  let stagesComplete = 0;
  let requiredTotal = 0;
  let requiredFilled = 0;
  let blockedStages = 0;

  for (const spec of registry.stages) {
    const score = scores[spec.key];
    if (!score) continue;

    requiredTotal += score.requiredTotal;
    requiredFilled += score.requiredFilled;
    if (score.isComplete) stagesComplete += 1;

    if (score.missing.length) {
      blockedStages += 1;
      const addresses = stageAddresses(
        spec,
        draft.stages[spec.key],
        // Resolved from the draft, which is where the definition lives — the same copy
        // `localCompleteness` scored above, so the labels this walk keys on are the labels that list
        // holds.
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
          // A label the walk could not place still gets a link — to the stage, with nothing focused.
          // An item a designer cannot act on has failed, and landing on the right form with the box
          // unhighlighted is a far smaller failure than an entry that goes nowhere.
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

/**
 * The progress sentence, stated honestly rather than as a percentage.
 *
 * "82%" is the figure this screen must NOT lead with: it is the same number whether the remaining
 * 18% is one date field or a stage nobody has opened, and a designer reads it as "nearly there" in
 * both cases. Counting the stages and then counting the fields inside them gives the two numbers
 * that actually decide whether the afternoon is enough.
 */
export function readinessSummary(readiness: WorkshopReadiness): string {
  const { stagesComplete, stagesTotal, blocking, blockedStages } = readiness;
  const stages = `${stagesComplete} of ${stagesTotal} stages complete`;
  if (!blocking.length) return `${stages}; no required fields remain.`;
  const fields = `${blocking.length} required field${blocking.length === 1 ? "" : "s"}`;
  const where = `${blockedStages} stage${blockedStages === 1 ? "" : "s"}`;
  return `${stages}; ${fields} remain${blocking.length === 1 ? "s" : ""} in ${where}.`;
}

/**
 * How an item names the exact box, for a reader who is deciding whether to click it.
 *
 * Kept beside the model rather than in the component because the e2e spec asserts on it: the promise
 * this screen makes is that it NAMES the field, and a test that only checked a link existed would
 * pass against a list of twenty-two rows reading "something is missing".
 */
export function itemFieldName(item: ReadinessItem): string {
  const address = item.address;
  if (!address) return item.label;
  if (!address.rowTitle) return address.fieldLabel;
  return `${address.fieldLabel} · ${address.rowTitle}`;
}
