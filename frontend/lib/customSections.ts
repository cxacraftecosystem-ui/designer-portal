/**
 * Designer-defined sections and fields, on the web: the definition, and the twelve types it may use.
 *
 * The rules are `backend/app/services/custom_sections.py` and
 * `docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md` §4. Nothing is re-decided here. This module is the
 * browser's half of two routes, the adapter that lets one custom section render through the form
 * this app already has, and the plain words the authoring screen says the server's refusals in.
 *
 * WHY THIS IS ITS OWN MODULE AND NOT A SECTION OF `designWorkshops.ts`. That file's own header
 * states what it is: the client for the FIELD REGISTRY — 22 stages, 43 entities, 496 typed fields
 * declared in Python — plus the cache that keeps it off the wire. A custom definition is the
 * opposite kind of thing: it is per workshop, it is written by a designer at a keyboard rather than
 * deployed, and it carries its own digest which must never enter `registryVersion`. Mixing the two
 * into one module would put the one string that must not be conflated (`customSchemaVersion` versus
 * `schemaVersion`) in the same scope, which is exactly the mistake that has to be impossible here —
 * `stageSpecFor` reads `registryVersion` as a KEY into the registry object store, so a composite
 * value would miss every cached registry and every stage would render from "whatever this browser
 * happens to hold". `lib/aiLayers.ts` was split out on the same argument.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * EVERY KEY BELOW WAS READ OFF THE SERVER'S OWN SERIALISERS ON 2026-08-12, NOT REMEMBERED:
 * `field_payload`, `section_payload` and `definition_payload` in
 * `backend/app/services/custom_sections.py`, and `CustomFieldIn`/`CustomSectionIn`/`CustomSectionsIn`
 * in `backend/app/schemas/design_workshops.py`. This repository has shipped a client listening on
 * five keys an endpoint never sent — `DwIdentityOcrResult` declared `number`, `documentType` and
 * `name`, and a PERFECT read of an identity card was reported to the designer as unreadable, on both
 * clients, because decoding JSON ignores unknown keys and nothing threw. So: `stageKey` not `stage`,
 * `supersededById` not `supersededBy`, and an option is `{value, label}` — where the label ARRIVES
 * already resolved (`field_payload` emits `CustomOption.display`, which is the token itself when
 * nobody wrote a label, deliberately, rather than inventing "Cotton" from `COTTON` — a word that
 * would go into a ministry document) and may be sent BACK empty, which the server reads as "print the
 * token". The asymmetry matters in one place only: an editor must not print a label it never typed
 * back at the designer as though they had.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * THE TWO ASYMMETRIES THAT WILL BITE ANYONE EDITING THIS FILE.
 *
 * 1. **WHAT COMES BACK IS NOT WHAT MAY BE SENT.** `field_payload` emits `id`, `retired` and
 *    `supersededById`; `CustomFieldIn` is an `APIModel`, i.e. `extra="forbid"`, and declares none of
 *    the three. So handing a fetched definition straight back to the PUT is a 422 on every field —
 *    and the strictness is deliberate on the server's side, because "a definition is written at a
 *    keyboard by somebody who can see the response, and a silently ignored `requred: true` is a
 *    required field that never becomes required". {@link definitionBody} is the only thing that may
 *    build a request body, and it exists so no screen has to remember which keys are which.
 * 2. **RETIRED SECTIONS AND FIELDS ALWAYS ARRIVE AND MUST NEVER BE DROPPED.** `definition_payload`
 *    includes them on purpose: a copy missing every answer given under a superseded wording makes
 *    two copies of one report disagree about the fieldwork, with nothing in either saying so. A
 *    client OFFERS the live ones and PRINTS the retired ones that hold an answer. {@link liveFields}
 *    and {@link retiredFields} are the two halves, and no caller may filter by hand.
 */

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
// `isUnreachable`, NOT `isTransient`: the latter answers "is it worth retrying" and says yes to every
// 5xx, which is how a definition the server had ANSWERED and refused came to be reported as a lost
// connection — sending a designer to look at their signal while the real fault sat in the response.
import { isUnreachable } from "@/lib/offline";

/* ────────────────────────────────────────────────────────────────────────────
 * The reserved key, and the twelve types
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The entity key the custom answers travel under, and the one the server files their errors beneath.
 *
 * `custom_sections.CUSTOM_ENTITY_KEY`. It is a `DwStageEntry` row of its own, one per (workshop,
 * stage), rather than an object nested inside the stage's singleton — and the reason is the installed
 * fleet rather than taste. `save_stage` replaces a singleton's `data` wholesale, so a client one
 * release behind, sending no `custom` key, would have deleted every custom answer on the stage with
 * nothing in `droppedKeys` to say so. With a separate row an old client sends no `_custom` entry and
 * no `_custom` row is touched.
 *
 * The leading underscore is this repository's existing reservation — `_clientKey`, `_entryId` and
 * `_ordinal` already mean "the protocol's own, not a designer's key" on both clients — and
 * {@link CUSTOM_KEY_PATTERN} refuses anything that does not start with a lower-case letter, so a
 * designer's key can never collide with it.
 */
export const CUSTOM_ENTITY_KEY = "_custom";

/**
 * The field types a designer may declare, and the whole list. `custom_sections.V1_FIELD_TYPES`.
 *
 * **NO MEDIA, NO RICH_TEXT, NO REF, NO GEO, AND EACH EXCLUSION IS A DEFECT ALREADY PAID FOR ONCE.**
 * Five separate walkers translate a local media reference into a server id — the server's
 * `_media_ids`, Android's `wireData`, and this client's `unresolvedMediaRefs`, its draft-resolve and
 * its `rewriteMediaRefs` — and every one of them enumerates the media-typed fields OF THE ROW'S
 * REGISTRY ENTITY and reads them at the top level of the row. None can see a value that is not a
 * registry field, so a custom photograph would sync as a `dwlocal:` reference resolving to nothing:
 * the save reports success and the picture is simply absent from the .docx, which the designer
 * learns from the officer who received it. REF is out because `ref_resolves` is supplied by the
 * REPORT and by nothing else, so a dangling custom reference would read FILLED on every form and
 * UNFILLED in the document — the 144/144-beside-"Not recorded."-thirty-six-times defect, verbatim.
 *
 * THE ORDER IS THE ORDER THE AUTHORING SCREEN OFFERS THEM IN, which is why it is an array and not a
 * Set: the commonest answer first, so the designer adding "how many looms" does not scroll.
 */
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

/** The two types that carry an option list, and the only ones that may. */
const OPTION_TYPES: ReadonlySet<string> = new Set<string>(["ENUM", "MULTI_ENUM"]);

/** The types a numeric bound is ever checked against — `custom_sections._BOUNDED_TYPES`. */
const BOUNDED_TYPES: ReadonlySet<string> = new Set<string>(["INT", "DECIMAL", "MONEY", "PERCENT"]);

/** The types `coerce_value` applies `maxLength` to — `custom_sections._LENGTH_TYPES`. */
const LENGTH_TYPES: ReadonlySet<string> = new Set<string>(["TEXT", "LONG_TEXT"]);

/**
 * Is this token one a designer may declare in v1?
 *
 * **ASKED AGAINST THE TWELVE AND NOT AGAINST WHAT THIS BUILD CAN DRAW**, which is a wider set and
 * the wrong question. `FieldInput` has a working branch for GEO, IMAGE, REF and RICH_TEXT — so a
 * definition naming one of those, from a server that had moved past v1, would render a real editable
 * control for a value none of the five media walkers can see. The v1 boundary is about what can
 * safely ROUND TRIP, not about what can be painted, so anything outside these twelve is read-only
 * whether this build knows the token or not. See {@link customFieldToDwField}.
 */
export function isV1CustomType(type: string): type is DwCustomFieldType {
  return V1_TYPE_SET.has(type);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Bounds — the same constants the service declares, imported in spirit
 *
 * Restated here rather than fetched because they bound what a BOX may hold and the box is drawn
 * before any request is made. A bound this file and the server disagreed about would be a 422 saying
 * something the screen's own counter contradicts: the box stops at 160, the designer cannot type the
 * character that would explain the refusal, and the only way out of the screen is to guess.
 *
 * THE NINE BELOW ARE READ OFF `custom_sections.py` AND COMPARED, ONE BY ONE, BY THE TEST NAMED "the
 * nine bounds are the service's own values, read off the Python rather than remembered" in
 * `e2e/custom-sections-unit.spec.ts` — which also asserts that there are still exactly nine, so a
 * TENTH bound added on the server cannot sit unmirrored here. **That test did not exist when this
 * comment first claimed it did**, and a comment promising a guard nobody wrote is worse than no
 * comment at all: the next person to move a bound reads it and believes the drift will be caught.
 * ──────────────────────────────────────────────────────────────────────────── */

export const MAX_CUSTOM_SECTIONS = 12;
export const MAX_CUSTOM_FIELDS_PER_SECTION = 60;
export const MAX_CUSTOM_OPTIONS = 100;
export const MAX_CUSTOM_LABEL_CHARS = 160;
export const MAX_CUSTOM_HELP_CHARS = 600;
export const MAX_CUSTOM_TITLE_CHARS = 160;
export const MAX_CUSTOM_DESCRIPTION_CHARS = 600;
export const MAX_CUSTOM_KEY_CHARS = 40;
export const MAX_CUSTOM_UNIT_CHARS = 24;

/**
 * How long one option's stored TOKEN may be — and the one bound on this screen that the service does
 * not declare.
 *
 * **IT LIVES IN THE ENVELOPE AND NOWHERE ELSE**: `CustomOptionIn.value` is
 * `Field(min_length=1, max_length=64)` in `backend/app/schemas/design_workshops.py`, and
 * `validate_definition` — which this file's {@link customDefinitionProblems} is otherwise a complete
 * mirror of — never mentions it. So it was the one refusal the editor could not predict, and it
 * arrived as a raw pydantic 422 against a list a designer had built one line at a time in a textarea
 * with no counter on it. Its sibling, the option's printed form, is bounded by
 * {@link MAX_CUSTOM_LABEL_CHARS} in the same envelope — shared with a field's label, which is why only
 * the token's own number is declared here.
 *
 * Both are pinned against that schema file by "an option's own two bounds are refused here, because
 * they live only in the envelope" in `e2e/custom-sections-unit.spec.ts`.
 *
 * NO OTHER ENVELOPE BOUND IS MISSING, checked by reading `CustomFieldIn`, `CustomSectionIn` and
 * `CustomSectionsIn` through: `type` (24) and `tier` (16) are unreachable because a type outside the
 * twelve is already refused by name and the tier is a three-way control; `sortOrder` (≤10,000) is
 * assigned positionally by {@link definitionBody} under caps of 60 and 12; `stageKey` (64) comes from
 * the registry rather than a keyboard; `maxLength` (≤100,000) has no control on the authoring screen
 * at all, so nothing can raise it past the server's own; and every `min_length=1` is already a
 * "has no …" refusal above. The three list caps are the three constants already declared.
 */
export const MAX_CUSTOM_OPTION_VALUE_CHARS = 64;

/**
 * What a key may look like — `custom_sections.KEY_PATTERN`, character for character.
 *
 * Lower-case first letter, then letters and digits, at most 40. Narrowed to lower-case at the front
 * so no designer key can collide with the `_`-prefixed protocol keys or with
 * {@link CUSTOM_ENTITY_KEY} itself.
 */
export const CUSTOM_KEY_PATTERN = /^[a-z][A-Za-z0-9]{0,39}$/;

/* ────────────────────────────────────────────────────────────────────────────
 * The definition, as the wire carries it
 * ──────────────────────────────────────────────────────────────────────────── */

/** One option of a designer's own choice list. `label` may be "" — see the file header. */
export type DwCustomOption = { value: string; label: string };

/**
 * One designer-defined field, exactly as `field_payload` serialises it.
 *
 * Every key is present on every field, INCLUDING the defaults — deliberately the opposite of
 * `field_to_dict`'s omit-the-defaults rule for the 119 KB registry, because "a client that has to
 * supply its own default for an absent key is a client that will eventually supply a different one
 * from the server's". So nothing here is optional, and a reader that finds `help` missing is looking
 * at a payload from something that is not this endpoint.
 *
 * `type` is `string` and not {@link DwCustomFieldType}, and that is the honest typing rather than a
 * looser one: JSON is `any` at the fetch boundary, the server may move to v1.1 while this build is
 * in a village, and a union here would only mean the compiler believed a token it had never seen.
 */
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
  /** Stopped being asked; its answers stay readable and printable. Never dropped from a screen. */
  retired: boolean;
  /** The field that replaced this one when its LABEL was rewritten after it had answers. */
  supersededById: string | null;
};

/** One block of designer-defined questions, as `section_payload` serialises it. */
export type DwCustomSection = {
  id: string;
  key: string;
  /** The `StageSpec.key` these answers belong to. Never empty — the server refuses a section with no stage. */
  stageKey: string;
  title: string;
  description: string;
  sortOrder: number;
  /** Bumped whenever something under this section was superseded or retired. */
  revision: number;
  retired: boolean;
  fields: DwCustomField[];
};

/** A whole definition, as `definition_payload` returns it. */
export type DwCustomDefinition = {
  /**
   * The digest of this definition, and "" for a workshop that has never had one.
   *
   * EMPTY IS A REAL VALUE AND NOT A MISSING ONE: `custom_schema_version` returns "" rather than a
   * hash of nothing, so that "I hold nothing" and "there is nothing to hold" are distinguishable —
   * which is the same distinction Android's `DwQuestionnaireCopy` needed three states for.
   */
  customSchemaVersion: string;
  sections: DwCustomSection[];
  fetchedAt: string;
};

/** What the PUT adds to the definition it returns: what the write actually DID. */
export type DwCustomSaveResult = DwCustomDefinition & {
  created: number;
  superseded: number;
  retired: number;
  removed: number;
};

/*
  THERE IS DELIBERATELY NO EXPORTED "EMPTY DEFINITION" CONSTANT HERE, and one was removed rather than
  left as scaffold. Every reader of a definition on this side takes `DwCustomDefinition | null`, where
  NULL MEANS "this browser has not read one" — a state that must stay distinguishable from "the server
  has one and it is empty", because the two look identical from inside a tab with no signal and warning
  on both puts an apology on the majority of workshops. An empty constant is exactly the value a tired
  reader reaches for as a default, and every such default silently asserts the second fact. The server
  has an `EMPTY_DEFINITION` because it is answering a question it can always answer; this side cannot.
*/

/* ────────────────────────────────────────────────────────────────────────────
 * Reading one stage's questions out of a definition
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sections asked at one stage, retired ones included, in the order they are asked.
 *
 * **EVERY FIELD OF A RETIRED SECTION IS FORCED TO `retired` HERE, AND THIS IS THE ONE PLACE THAT
 * HAPPENS.** `section_payload` does not do it: a section's `isActive` and a field's are separate
 * columns, and `field_payload` reports each field's own flag — so a retired section can arrive
 * carrying fields that call themselves live. The server's `fields_for` forces the flag for exactly
 * that reason and says so; forcing it at the same door on this side is what stops the two halves of
 * this client disagreeing about one section. Without it `customSectionEntity` would draw a retired
 * section as an ordinary editable form (its `liveFields` would be non-empty) while
 * {@link customFieldsForStage} scored none of them and `retiredFields` printed none of them either:
 * a designer answering questions nobody is being asked, into a bucket the completeness bar ignores.
 */
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

/**
 * Every field of every section of one stage, in the order they are asked — `fields_for` on the
 * server, including its one subtlety.
 *
 * **RETIRED SECTIONS ARE INCLUDED AND EVERY FIELD UNDER ONE IS FORCED TO `retired`** — the forcing
 * itself lives in {@link sectionsForStage}, one door up, so that the form and the scorer cannot read
 * two different answers off one payload. Reading this as "live sections only" was a silent data-loss
 * bug on the server and it is worth naming here because the client can commit it independently: a
 * section is retired precisely BECAUSE somebody answered it (an answered section is retired, never
 * deleted), so its keys are exactly the keys the `_custom` row still holds. Left out of this list they
 * become keys the definition does not carry — and this client's next ordinary save of that stage would
 * send them, have them dropped, and get them back in `droppedCustomKeys`.
 */
export function customFieldsForStage(
  definition: DwCustomDefinition | null | undefined,
  stageKey: string
): DwCustomField[] {
  return customStageBlocks(definition, stageKey).flatMap((block) => block.fields);
}

/** One stage's questions, still grouped by the section that asks them. */
export type DwCustomStageBlock = { section: DwCustomSection; fields: DwCustomField[] };

/**
 * The same questions {@link customFieldsForStage} scores, still paired with their sections.
 *
 * **ONE OWNER OF THE ORDER, TWO VIEWS OF IT**, and that is why the flat version is defined as this
 * one's flatten rather than the two being written separately. The scorer needs a flat list and files
 * every label bare; the readiness screen's address walk needs to know WHICH section asks a question, so
 * it can build the synthetic entity key the stage form will render that section under and land the
 * highlight on the right box. Two independent walks would be two orderings, and `missing` is printed in
 * the scorer's order — so the day they diverged, the readiness screen would send a designer to the
 * second question when the list said the first.
 */
export function customStageBlocks(
  definition: DwCustomDefinition | null | undefined,
  stageKey: string
): DwCustomStageBlock[] {
  // The retired-section forcing is NOT repeated here: {@link sectionsForStage} does it for every
  // reader, including the form. It used to be done here and only here, which meant the scorer and the
  // form were reading two different answers to "is this question still asked" off one payload.
  return sectionsForStage(definition, stageKey).map((section) => ({
    section,
    fields: section.fields.slice().sort((a, b) => a.sortOrder - b.sortOrder || a.key.localeCompare(b.key))
  }));
}

/**
 * The part of a fetched definition an EDITOR may put boxes around: the live sections, each holding
 * only its live fields.
 *
 * **THE STORED COPY IS STILL THE WHOLE PAYLOAD, AND ONLY THE EDITABLE PROJECTION IS THIS.** The two
 * are needed side by side: what an edit COSTS is decided against the whole stored definition (a
 * retired key cannot be re-used, and whether a question has answers is what decides retire versus
 * delete), while what a designer may TYPE INTO is only what is still being asked. Handing the whole
 * payload to the boxes instead — which is the obvious thing, since it is what the fetch returned —
 * puts a retired question on screen as an editable row with an unlocked key box, whose every edit is
 * then dropped by {@link definitionBody} without a word: the designer rewords a question that is
 * evidence, presses Save, and is told nothing changed. It also counts retired questions against the
 * sixty-per-section cap the server applies to the body alone, and it re-sends retired SECTIONS, which
 * un-retires them.
 *
 * A retired section and a retired field are not dropped from the SCREEN — they are printed read-only
 * beside the boxes, because that is what retiring means and because their keys cannot be re-used.
 */
export function editableSections(sections: readonly DwCustomSection[]): DwCustomSection[] {
  return sections.filter((section) => !section.retired).map((section) => ({ ...section, fields: liveFields(section) }));
}

/** The fields still being asked. What a form OFFERS and what completeness counts. */
export function liveFields(section: DwCustomSection): DwCustomField[] {
  return section.fields
    .filter((field) => !field.retired)
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder || a.key.localeCompare(b.key));
}

/**
 * The fields no longer asked. What a form PRINTS, and only where an answer stands against them.
 *
 * A retired field with no answer is a question the designer removed and nobody ever filled in;
 * drawing it would put a crossed-out row on the screen for no reason. A retired field WITH an answer
 * is evidence recorded under a wording that no longer appears anywhere else, and hiding that is how
 * two copies of one report come to disagree about the fieldwork.
 */
export function retiredFields(section: DwCustomSection, values: DwEntryData): DwCustomField[] {
  return section.fields
    .filter((field) => field.retired && isFilled(values[field.key]))
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder || a.key.localeCompare(b.key));
}

/**
 * Which of these fields hold an answer, judged by the completeness scorer's own test.
 *
 * `answered_keys` on the server, and deliberately the SAME question the readiness screen asks: a
 * field the scorer counts as filled is a field whose wording is now evidence, and it is that fact
 * which decides whether an edit supersedes or merely edits. Two different answers to "is this
 * answered" would let the editor promise an edit the server converts into a supersede.
 *
 * **SEVERAL BUCKETS, UNIONED, AND THE SECOND ONE IS WHY THIS IS VARIADIC.** The obvious caller hands
 * in the SERVER's `_custom` bucket, because the server is what the definition edit will be applied
 * against — and that was the whole of it, which made this function answer "has anything reached the
 * office" while the editor asked it "is anything at stake". The two differ for exactly as long as a
 * stage sits in this browser's outbox, which is the fortnight the fieldwork happens in:
 *
 *   a designer answers a custom question over two weeks in a cluster; the stage cannot sync because a
 *   photograph on it is still uploading, so the pass holds the whole stage back. Back on wifi they open
 *   the definition editor and remove that question. Nothing on the server has ever held an answer under
 *   its key, so the button says **Delete** rather than Retire and the dialog says *"Nobody has answered
 *   it, so nothing is lost."* The PUT deletes the field row. The photograph finishes, the stage syncs,
 *   the server drops the key as unknown and writes the row without it — and the sync pass reports
 *   success, because it reads `saved.errors` and nothing else. The fortnight is gone, silently, and the
 *   screen that caused it had promised the opposite in writing.
 *
 * So a key is answered if ANY bucket this device can see holds a value for it. A caller may pass
 * `undefined` for a bucket it does not hold — a stage record written before this feature carries no
 * `custom` key at all — rather than inventing an empty one at every call site.
 *
 * IT IS A UNION AND NEVER AN OVERLAY: a local edit that CLEARED a key the server still holds leaves it
 * answered, which is correct and not an oversight. The server's row is what the PUT's retire/delete
 * rules are applied against, and it is still standing — the clearing has not been sent, and may never
 * be. Erring the other way would offer a free Delete for evidence the office is still holding.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The adapter: one custom section, rendered by the form this app already has
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One custom field as a `DwField` the existing form can draw.
 *
 * WHY AN ADAPTER AND NOT A SECOND RENDERER. `FieldInput` is 1,400 lines and `EntityForm` another
 * 700, and between them they already do everything a custom section needs: the tier split with
 * ADVANCED behind a disclosure, `help` and "Measured in X." wired through `aria-describedby`, a
 * per-field error with `role="alert"` and `aria-invalid`, full- versus half-width cells decided from
 * the type, and MONEY read as a string so "1250.10" survives the round trip. A second renderer would
 * be a second set of answers to all of that, and the day the two disagreed a designer would see one
 * question behave unlike every other question on the same screen.
 *
 * **A TYPE OUTSIDE THE TWELVE IS RE-TOKENISED SO IT CANNOT REACH A WORKING CONTROL.** This is the
 * load-bearing line of the whole adapter. `FieldInput` switches on `field.type` over a closed union
 * with no `default`, so it has a real, working branch for GEO, IMAGE, REF and RICH_TEXT — and
 * handing it one of those would draw an editable map card or a camera button for a value that none
 * of the five media walkers can see, which is the `dwlocal:`-reference-resolving-to-nothing failure
 * v1 exists to avoid. So anything not in {@link V1_CUSTOM_TYPES} is passed through
 * {@link unsupportedFieldType}, which produces a token no branch matches, and it lands in
 * `FieldInput`'s `default:` arm as a disabled box with a sentence naming the type. The raw token
 * travels INSIDE that token so the sentence can name it: a note that will not say what the type was
 * is a note a designer cannot report.
 *
 * `id` is not carried across. `DwField` has no such key and the form has no use for one — the answer
 * is stored under `key`, which is what makes the answer survive a rewording.
 */
export function customFieldToDwField(field: DwCustomField): DwField {
  return {
    key: field.key,
    label: field.label,
    type: isV1CustomType(field.type) ? field.type : unsupportedFieldType(field.type),
    tier: field.tier,
    required: field.required,
    // Emitted as "" by the server rather than omitted, and `FieldInput` tests them for truthiness,
    // so passing the empty strings through is correct and costs no branch.
    help: field.help,
    unit: field.unit,
    options: field.options,
    maxLength: field.maxLength,
    // `minValue`/`maxValue` are `number | null` on the wire and `number | undefined` on `DwField`.
    // Mapped rather than cast: `null` in a `min` attribute renders as the string "null" on some
    // engines, which silently makes every number in the box invalid.
    ...(field.minValue === null ? {} : { minValue: field.minValue }),
    ...(field.maxValue === null ? {} : { maxValue: field.maxValue })
  };
}

/**
 * One custom section as a synthetic SINGLETON entity, so `EntityForm` can render it unchanged.
 *
 * THE ENTITY KEY IS PER SECTION AND IS NOT `_custom`. Two sections on one stage rendered under one
 * entity key would collide in three places at once: the `data-dw-field` anchors `lib/workshopSearch`
 * resolves against, the `advanced-<key>` id of the disclosure, and `findMissingViews`' entity
 * lookup. The ANSWERS still live in one flat bucket keyed by field key — field keys are unique
 * across the whole workshop, which the server enforces for exactly this reason — so the synthetic
 * key is a rendering identity only and never a storage one.
 *
 * `findMissingViews` returns nothing for an entity key it does not recognise, so `MissingViewsHint`
 * draws nothing here of its own accord; the media affordances are unreachable because v1 declares no
 * media type at all.
 *
 * ONLY THE LIVE FIELDS GO IN. A retired field is not offered again — that is what retiring means —
 * and its answer is printed by {@link retiredFields}' half of the screen instead.
 */
/**
 * The rendering identity of one section, and the one place that string is built.
 *
 * Both the adapter below and the readiness screen's address walk need it, and they must agree
 * character for character or a "go to this question" link lands on a stage with nothing highlighted.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The two routes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A definition, fetched once per workshop per tab and thereafter served from memory.
 *
 * SHAPED EXACTLY LIKE `fetchStageRegistry`, and the three properties it copies are all load-bearing
 * for the same reasons that function records:
 *
 *  - **A shared in-flight promise**, so the stage page's several components mounting in one commit
 *    issue one request between them rather than three.
 *  - **`.finally` clears it whether it resolved or threw.** A rejected promise parked here would
 *    serve the same failure to every later caller for the life of the tab, so a connection that came
 *    back would never be noticed and the section would stay permanently absent.
 *  - **On a refresh whose digest is unchanged, the PREVIOUSLY CACHED OBJECT is returned.** Identity
 *    matters: the sections and their field lists are held in `useMemo` dependency arrays, and a
 *    structurally identical but referentially new object re-runs every one of them and rebuilds
 *    every adapter result on the cheapest laptop in the room. `customSchemaVersion` is a content
 *    digest, so an equal digest IS the same definition.
 *
 * It is keyed by workshop, unlike the registry, because a definition belongs to one workshop.
 */
/*
  TWO MAPS AND NOT ONE RECORD PER WORKSHOP, because a resolved definition and a request in flight are
  different states and one object holding both has to invent a placeholder for whichever is absent.
  The first version of this did exactly that, and the placeholder — an empty definition — was
  reachable by `peekCustomDefinition`: a stage form would have read "this workshop has no custom
  questions" off a request that had not answered yet, which is the same silent emptiness the whole
  three-state argument on the handset exists to prevent.
*/
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
      // Cleared whether it resolved or threw. A rejected promise left parked here would serve the
      // same failure to every later caller for the life of the tab, so a connection that came back
      // would never be noticed and the section would stay permanently absent — which reads on screen
      // as "this workshop has no custom questions".
      definitionsInFlight.delete(workshopId);
    });

  definitionsInFlight.set(workshopId, request);
  return request;
}

/*
  NO `peekCustomDefinition` TWIN OF `peekStageRegistry`, and its absence is deliberate. That function
  exists because the registry is one document every screen in the feature needs and any of them may
  legitimately ask "is it already here". A definition is per workshop and reaches a screen one way only —
  through {@link loadCustomDefinition} in the draft store, which is also what keeps the disk copy and the
  three-state source in step. A second door into this cache would be a screen holding a definition with no
  idea whether it came from the network, from disk, or from nowhere.
*/

/**
 * Seed the in-memory cache from a definition that did NOT come off the wire — the copy the draft
 * store keeps in IndexedDB, served when a tab has been out of signal since it opened.
 *
 * Returns whatever is now cached rather than what was passed in, which is the point: a definition of
 * the same digest hands back the ALREADY-CACHED object, so the identity contract above survives an
 * offline fallback. `adoptStageRegistry` is the same function for the registry, for the same reason.
 */
export function adoptCustomDefinition(workshopId: string, definition: DwCustomDefinition): DwCustomDefinition {
  const held = cachedDefinitions.get(workshopId);
  if (held && held.customSchemaVersion === definition.customSchemaVersion) return held;
  cachedDefinitions.set(workshopId, definition);
  return definition;
}

/**
 * One section in the shape `CustomSectionIn` accepts, and nothing else.
 *
 * **THE ENVELOPE IS `extra="forbid"`**, so this function is the difference between a save and a 422:
 * `id`, `retired` and `supersededById` arrive on every fetched field and are refused on every sent
 * one. That asymmetry is deliberate on the server's side and is stated in its docstring; the danger
 * on this side is that a fetched definition looks exactly like a sendable one, so an editor that
 * spread a fetched section into a request body would compile, read correctly, and 422 with a
 * message about a key the designer never typed.
 *
 * WHAT IS ABSENT FROM THE BODY IS NOT DELETED. A field the body no longer names is RETIRED if it has
 * answers and removed only if it does not, and rewording an answered field SUPERSEDES it. So this
 * function must be given the WHOLE set every time — the route is a whole-set PUT because
 * "one definition, one digest" has to be atomic, and a definition assembled from six PATCHes has six
 * intermediate digests each of which some handset can fetch and cache as though it were the whole.
 */
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
    /*
      THE DIGEST THIS EDITOR LOADED, SENT BACK SO THE PUT CANNOT SILENTLY OVERWRITE A DEFINITION IT
      NEVER SAW. A whole-set replace is last-write-wins by construction: measured on the wire,
      designer 1 saves ['dye'], designer 2 adds ['dye','looms'], and designer 1's tab — open since
      before the second save — presses Save and the `looms` section and both its fields are REMOVED
      under a 200, correctly by the server's own rule, because nothing had answered them yet.

      OMITTED RATHER THAN SENT AS null WHEN THERE IS NONE. The field is optional on
      `CustomSectionsIn` so that already-shipped clients keep working, and the server reads ABSENT as
      "this client predates the check" — but it is typed `str | None`, so an explicit null would also
      pass. Omitting keeps the wire honest about which of the two this is, and `extra="forbid"`
      means a key spelled wrong fails loudly here rather than being ignored into last-write-wins.
    */
    ...(customSchemaVersion ? { customSchemaVersion } : {}),
    /*
      A RETIRED SECTION IS NEVER NAMED IN A BODY, AND NAMING ONE UN-RETIRES IT.

      This is the same rule `liveFields` applies to a field, one level up, and it has to be here rather
      than in the editor because it is a property of the REQUEST: `plan_definition` reads a named
      section as "ask this", and `apply_definition_plan` therefore writes `isActive: True,
      retiredAt: None` for it — deliberately, so that re-adding a section a designer had removed is not
      a silent no-op. The consequence for a client is the other way round. `definition_payload` returns
      retired sections on every read (it must: their keys are the keys the `_custom` row still holds),
      so an editor that showed what it fetched and sent back what it showed would UN-RETIRE every
      retired section of the workshop on the next save of any unrelated question — the section starts
      being asked again, with no live fields, and the designer is told only that their new question was
      added. Retiring is not an edit that can be undone by accident.

      A caller that genuinely means to re-ask a retired section clears the flag first, which is one
      explicit line rather than an omission nobody can see.
    */
    sections: sections
      .filter((section) => !section.retired)
      .map((section, index) => ({
        key: section.key,
        title: section.title,
        stageKey: section.stageKey,
        description: section.description,
        // Positional rather than the stored number, so dragging a section up is what decides the order
        // and a definition whose every `sortOrder` was left at 0 does not print in whatever order the
        // database handed the rows back — which is stable until it is not.
        sortOrder: index,
        /*
          THE FIELD ORDER IS THE ARRAY'S, WHICH IS WHY THIS DOES NOT GO THROUGH `liveFields`, and the
          exception is worth the words. `liveFields` SORTS by the stored `sortOrder` — right for the
          read path, where the server has already sorted and the flag is all a form needs to ask about.
          Here it would be a second owner of one fact: the number below is assigned from the position,
          so sorting by the old number first means the position can never change it. An editor's "move
          this question up" swaps two array entries and leaves both stored numbers alone, and every one
          of those moves was silently discarded on the way to the wire — the screen showed the new
          order, the save reported success, and the form went on asking in the old one. Two fields left
          at `sortOrder: 0` were worse: they came back ordered by `localeCompare` on their keys, which
          is an order nobody chose.
        */
        fields: section.fields
          .filter((field) => !field.retired)
          .map((field, fieldIndex) => fieldBody(field, fieldIndex))
      }))
  };
}

/**
 * Replace this workshop's whole custom definition.
 *
 * The in-memory cache is updated from the response rather than from the request, because the two are
 * not the same document: the server mints a new key for a superseded field, retires what the body no
 * longer names, and returns the definition as it now STANDS. An editor that trusted its own request
 * would go on showing the old wording under the old key and would send it again on the next save,
 * which is the loop `_live_successor` exists to break.
 *
 * `customSchemaVersion` IS THE DIGEST THE CALLER LOADED, and passing it is what turns this from a
 * last-write-wins replace into a checked one. Omit it and the server keeps the old behaviour, which
 * is what every already-shipped client gets; pass it and a definition changed by somebody else since
 * the caller read it comes back 409 with both digests named, instead of 200 with their work removed.
 * Pass what `fetchCustomDefinition` returned — never a digest computed here.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Refusals
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sentence to show when the definition routes refuse, and the LIST when they refuse several things.
 *
 * WHY THIS EXISTS RATHER THAN `describeApiDetail` OR `readableError`, AND WHY IT IS NOT A THIRD COPY OF
 * EITHER. Both of those read a detail that is a string, a FastAPI 422 list, or an object with a
 * `message`. This route's 422 is a fourth shape they all collapse: `{"message": "This definition cannot
 * be saved yet", "problems": [...]}`, where `problems` holds EVERY rule the definition breaks. The
 * server returns all of them at once deliberately — "a designer fixing a form one 422 at a time is a
 * designer who gives up on the third round trip" — and `describeApiDetail` returns only the `message`,
 * so a designer would be told their definition cannot be saved and not one word about why. This reads
 * the half that matters and delegates everything else to `ApiError.message`, which is already
 * `describeApiDetail`'s output.
 *
 * THE SERVER'S OWN WORDS WIN WHEREVER IT SPOKE. Every refusal here is already a sentence naming the next
 * move — *"`craftName` is already a field of stage 1 (Workshop setup → Craft name). Choose another
 * key."*, *"an answer is already recorded under that key on this stage, given to a different question …
 * Choose another key — the answer already given stays readable under the question it was asked as."* —
 * and rewording them on this side would give one rule two voices, which is how a client and a server come
 * to disagree about what a refusal means. The 409s in particular are sentences a designer must be able to
 * act on and are impossible to reconstruct from a status code.
 */
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
      "These questions are held on the server, so they cannot be changed without a connection. Nothing " +
        "has been queued for later — a definition edit decides what happens to answers already recorded, " +
        "and applying that decision days late against answers that have moved on would attach a question " +
        "to evidence given for a different one. Reconnect and save again; nothing you have typed here is " +
        "lost by waiting."
    ];
  }
  return [error instanceof Error && error.message.trim() ? error.message : fallback];
}

/* ────────────────────────────────────────────────────────────────────────────
 * What an edit will COST, said before the designer commits to it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What the server will do to one field, decided from the same two facts the server decides it from.
 *
 * THE WHOLE POINT IS THAT IT IS SHOWN BEFORE THE PUT, not explained afterwards. The server does not
 * REFUSE a rewording of an answered question — it CONVERTS it, retiring the original under its own
 * wording and minting a new field for the new one. `questionnaire_forms` records the failure that
 * rule prevents: "How many looms?" answered "12", reworded to "How many weavers?", and a ministry
 * report now states there are twelve weavers. A designer who presses Save and is then told their
 * question was superseded has been overruled by a machine on a screen about their own instrument,
 * and the next thing they do is press it again. `QuestionRow` in the questionnaire editor makes the
 * same call for the same reason, and this is that argument applied to a typed field.
 */
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
  /*
    RULE 2 ON THE SERVER, AND ITS LIST IS FIVE THINGS LONG: *"A field with answers may freely change
    its help, its required flag, its unit, its bounds and its position."* — `custom_sections.py`'s
    module docstring, repeated verbatim by `_plan_fields`' own RULE 2 comment.

    **THE TYPE IS IN NEITHER LIST, AND THIS COMMENT USED TO ADD IT.** That was not a harmless
    embellishment: it was repeated into the editor's note and into a sentence shown to the designer
    ("its wording is fixed from here; everything else can still change"), so a designer retyping an
    answered question was told in three places that it cost nothing.

    WHAT THE SERVER ACTUALLY DOES WITH A TYPE CHANGE — read rather than inferred from the symmetry.
    Nothing refuses it and nothing supersedes it: `_plan_fields` falls through to a plain EDIT, so the
    PUT is accepted and the recorded answer stays in the container in the shape the OLD type wrote it.
    The bill arrives one save later, on another screen: `plan_custom_write`'s phase one re-coerces
    every key the client sends, the client sends the stored answer back because the form drew it, and
    `coerce_value` raises on `int("about nine")` — returned as *"How many looms? is not a valid int"*,
    a refusal naming a value nobody typed on this visit. The rejected-value loop writes the old answer
    back so nothing is lost, and under `submit` that error 422s the WHOLE STAGE. `plan_custom_write`
    names this failure for a BOUNDS change in the same words and calls the stage "permanently
    unsubmittable".

    So FREE stays the honest answer to "what will the server CONVERT" — it converts nothing — and this
    function must not predict a conversion the server does not perform. Being told about it is
    {@link CustomDefinitionDiff.retypedFields}' job, which is a warning rather than a cost.
  */
  return { kind: "FREE" };
}

/**
 * Every rule this draft definition breaks, as sentences, before it is sent.
 *
 * A MIRROR OF `validate_definition` AND NOT A SECOND OPINION. The server is the authority and
 * answers with all of its refusals at once, deliberately — "a designer fixing a form one 422 at a
 * time is a designer who gives up on the third round trip". This function exists so the same
 * sentences appear as the designer types rather than after a round trip, and every one of them is a
 * rule the server enforces: nothing here refuses anything the server would accept, which is the line
 * this repository draws for every client-side check.
 *
 * WHAT IS MIRRORED AND WHAT IS NOT, ENUMERATED — because the previous version of this paragraph said
 * "a complete mirror" and it was not one, which is how the next person to add a server rule comes to
 * trust it rather than check it. Audit 2026-08-15 (MINOR, frontend) filed exactly that.
 *
 * MIRRORED: the section and field key patterns, the section/field/option counts, the uniqueness of
 * section keys, of field keys across the workshop and of labels within a stage, the title/description/
 * label/help/unit lengths, the v1 type whitelist, the required-must-be-Basic tier rule, the option
 * rules, the numeric bounds and the max-length applicability — and, since this audit, BOTH stage-
 * scoped collision checks: a custom key equal to a live field key of any entity of that stage, and a
 * custom label case-folding equal to a live label of that stage's SINGLETON entity. Those last two
 * need `stages`, which is why the parameter exists.
 *
 * NOT MIRRORED, and it cannot be from here: a key that already holds an answer given to a different
 * question. That needs the stored answers of every stage, which this screen does not load. It comes
 * back as a 409 with a sentence naming the way round it, and is shown verbatim.
 *
 * `stages` DEFAULTS TO EMPTY AND THAT IS THE HONEST DEGRADATION. With no registry loaded the two
 * stage-scoped checks simply do not run: this function then mirrors what it can and cannot invent a
 * refusal for a stage it has never seen. It must never be the other way round — refusing on absent
 * evidence would block a Save the server would have accepted, which is the one thing a pre-flight
 * check is forbidden to do.
 */
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
  // Field keys are unique across the WHOLE workshop, which is stricter than per section — the answer
  // container is one row per (workshop, STAGE), so two sections on one stage both declaring `q1`
  // would write into the same key of the same container: one answer, two questions, and no way to
  // tell which of them it answers.
  const seenFieldKeys = new Map<string, string>();
  // Labels collide only where the completeness scorer would collapse them, which is per stage.
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

    // Resolved once per section rather than once per field: the lookup is over 22 stages and the
    // answer is the same for every field of the section. `undefined` when the section names no stage
    // (already reported above) or when no registry was supplied.
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
  /** The registry stage this section's questions are asked at, when this browser holds the registry. */
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
      `Field ${where} is a ${field.type}, which a custom question cannot be yet. Choose one of: ` +
        `${V1_CUSTOM_TYPES.slice().sort().join(", ")}. Photographs, files, recordings, formatted ` +
        "text, coordinates and references to other records are deliberately not available: a " +
        "photograph attached to a custom question would sync as a reference that resolves to " +
        "nothing, the save would report success, and the picture would simply be absent from the " +
        "report."
    );
  }

  // Verbatim from the registry's own rule 3, and for its reason: the tiers exist so a workshop held
  // in a village without power can still produce a complete report, and a required Standard-tier
  // field makes the completeness gate unsatisfiable exactly where the app is most needed.
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
        `Field ${where} is a ${field.type} with ${field.options.length} option(s). A choice needs ` +
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
      // THE TWO BOUNDS THE SERVICE DOES NOT DECLARE — see {@link MAX_CUSTOM_OPTION_VALUE_CHARS}. They
      // are checked against the TRIMMED token and the TRIMMED label, because that is what the editor
      // sends: it splits each line on "|" and trims both halves, so a bound applied to the raw text
      // would refuse a line whose only excess is the space the designer typed after the bar.
      if (token.length > MAX_CUSTOM_OPTION_VALUE_CHARS) {
        problems.push(
          `Field ${where}'s option value ${JSON.stringify(token)} is longer than ` +
            `${MAX_CUSTOM_OPTION_VALUE_CHARS} characters. The option value is the token the answer is ` +
            "STORED as and it is not what anybody reads — shorten it, and put the words after the “|” " +
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

  // THE LABEL COLLISION IS THE ONE THAT ACTUALLY BITES, and it is checked exactly as wide as the
  // collapse and no wider. `missing` holds LABELS and is de-duplicated, while `requiredTotal` still
  // counts two — so two required questions sharing a label collapse into ONE row on the readiness
  // screen and in the report's Outstanding column while the count beside it says two: a document
  // disagreeing with itself about its own arithmetic, which this repository has already shipped once
  // (144/144 beside "Not recorded." printed thirty-six times). A custom field files its label BARE,
  // like a singleton field and unlike a collection field, so the collision is per stage.
  const label = field.label.trim().toLowerCase();
  if (label) {
    // A stage key and a label joined UNAMBIGUOUSLY. A plain separator character would be a
    // collision waiting to happen — a label may contain anything a designer types, including
    // whatever separator looked safe — and two different pairs colliding here would refuse a
    // definition that is perfectly sound.
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

  /*
    THE TWO STAGE-SCOPED COLLISIONS THE SERVER ENFORCES AND THIS FILE USED NOT TO. Audit 2026-08-15
    (MINOR, frontend). Neither could run while `fieldProblems` took only the section and the field:
    both need the registry stage the section's questions are asked at. Without them, `problems` was
    EMPTY for a colliding definition and the Save gate — `disabled={busy || problems.length > 0 ||
    …}` — was open, so a whole-set PUT was refused after the fact for a rule this editor exists to
    catch before it, taking every unrelated edit in the same body with it.

    `stage === undefined` covers two different innocent cases and neither may produce a refusal: a
    section that names no stage (already reported, and adding a second sentence about the same
    mistake is noise — the server skips these checks for the same reason), and a browser that has no
    registry loaded, which has no evidence either way.
  */
  if (stage) {
    /*
      RESERVED-KEY COLLISION, over EVERY entity of the stage, exactly as the server walks it.

      Not mechanically harmful under the current storage — the custom answers live in their own row
      and cannot shadow a core key — and refused anyway for two reasons that outlive that choice: a
      designer looking at one stage form must not see two different questions under one key, and
      refusing it keeps a later move to nested storage open instead of making it a data migration.

      `deprecated` is honoured because a retired registry field is not on the form and its key is
      free again; refusing against it would block a key nothing is using.
    */
    for (const entity of stage.entities) {
      const core = entity.fields.find((candidate) => candidate.key === field.key && !candidate.deprecated);
      if (core) {
        problems.push(
          `${JSON.stringify(field.key)} is already a field of stage ${stage.number} (${stage.title} → ` +
            `${entity.title}: ${core.label}). Choose another key.`
        );
      }
    }

    /*
      RESERVED-LABEL COLLISION, NARROWED TO THE SINGLETON ENTITY — and the narrowing is the whole
      correctness of the check, not a shortcut.

      `StageCompleteness.missing` holds LABELS and is de-duplicated, so two required fields sharing
      a label collapse into ONE row on the readiness screen, in the report's Outstanding column and
      in the export warnings, while `requiredTotal` still counts two. A SINGLETON field files its
      label bare and so does a custom field, so those two can collapse into each other. A COLLECTION
      field files `"{entity.title}: {label}"`, which cannot collide with a bare label at all — so
      refusing "Notes" on stage 13 because a prototype row has a "Notes" column would be a refusal
      with no failure behind it, on some of the most ordinary words a form uses. The server narrows
      it for exactly this reason; widening it here would refuse definitions the server accepts.
    */
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

/**
 * What one whole-set PUT will DO, worked out before it is sent.
 *
 * **THE POINT IS THAT IT IS SHOWN BEFORE THE SAVE AND NOT EXPLAINED AFTER IT.** The server does not
 * refuse an edit to an answered question — it CONVERTS it: what the body no longer names is retired if
 * it has answers and removed only if it does not, and rewording an answered question supersedes it. A
 * designer who presses Save and is then told two of their questions were kept under their old wording
 * has been overruled by a machine on a screen about their own instrument, and the next thing they do is
 * press it again. The questionnaire editor makes exactly this call, in `QuestionRow`, and states the
 * reason in the same words.
 *
 * A MIRROR OF `plan_definition`'s FIVE RULES AND NOT A SECOND OPINION. The server decides; this only
 * predicts, and it predicts from the same two inputs — the stored definition and which keys hold an
 * answer, judged by the completeness scorer's own test. Where it cannot see far enough it says nothing:
 * the supersede CHAIN is the server's (`_live_successor` walks it so a stale client's re-put is
 * idempotent rather than a second supersede), and a key that already holds an answer given to a
 * DIFFERENT question is a 409 whose sentence names the way round it.
 */
export type CustomDefinitionDiff = {
  created: number;
  superseded: Array<{ was: string; now: string }>;
  retiredFields: string[];
  deletedFields: string[];
  retiredSections: string[];
  deletedSections: string[];
  movedSections: string[];
  /**
   * Answered questions whose TYPE this edit changes — a WARNING, and the only entry here that is not
   * a conversion the server performs.
   *
   * It is in this list precisely because the server does nothing about it. A rewording is converted
   * and reported; a removal is converted and reported; a retype is accepted in silence, and the
   * consequence surfaces on a different screen on a later day as *"How many looms? is not a valid
   * int"* against a value nobody typed, with the stage 422 under `submit`. See {@link fieldEditCost}
   * for the mechanism and for where in the server it is written.
   *
   * `was` and `now` are the TOKENS, not the screen's plain words: the editor holds the mapping to
   * "Whole number", and the two must not both own it.
   */
  retypedFields: Array<{ label: string; was: string; now: string }>;
};

export function diffCustomDefinition(
  stored: readonly DwCustomSection[],
  next: readonly DwCustomSection[],
  /** `{stage key: the field keys that hold an answer}` — {@link answeredCustomKeys} per stage. */
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
    // Answers are read off the stage the section would WRITE INTO, which for a moved section is the new
    // one — a section may only be moved while nobody has answered it, so it inherits nothing.
    const answeredHere = answered[previous?.stageKey ?? section.stageKey] ?? new Set<string>();
    if (!previous) {
      diff.created += liveFields(section).length;
      continue;
    }
    if (previous.stageKey !== section.stageKey) {
      // THE ONE EDIT THE SERVER REFUSES OUTRIGHT rather than converting. The answers live in the
      // container of the stage the section is asked at, so moving it would leave them behind in the old
      // stage's row: still stored, no longer asked, no longer scored, invisible on every form.
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
      // A retype is reported ONLY where an answer stands against the question, because that is the only
      // place it can strand one — see {@link CustomDefinitionDiff.retypedFields}. It is not filed against
      // a supersede: that mints a NEW field under a new key, and a new field's type answers to nothing.
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

/**
 * Does anything under this section hold an answer — asked over EVERY field, retired ones included.
 *
 * **THE SERVER'S OWN TEST, WHICH IS WIDER THAN "THE QUESTIONS STILL BEING ASKED".** `plan_definition`
 * decides retire-versus-delete with `answered_here & {f.key for f in previous.fields}` and refuses a
 * move with the identical expression; `_keys_this_put_keeps` reads the same set to decide whether a
 * section's keys stay held. None of the three filters by `isActive`.
 *
 * This side asked it of `liveFields` — which is precisely the set that EXCLUDES the field holding the
 * answer in the commonest shape there is. After any supersede or any removal of an answered question,
 * a section's only answered key belongs to a retired field: the client then read the section as
 * untouched and the server read it as answered, and they disagreed about the one fact the whole screen
 * is built on. The screen offered to DELETE the section and promised nothing would be lost, the server
 * RETIRED it and kept every row, and the designer learnt the difference from a count afterwards — while
 * the keys the "deleted" section still holds go on being refused to any new question by name.
 *
 * Two callers and one expression, so the two cannot drift apart the way they did from the server.
 */
function sectionHoldsAnswer(section: DwCustomSection, answered: ReadonlySet<string>): boolean {
  return section.fields.some((field) => answered.has(field.key));
}

/**
 * The same question asked from an EDITOR's position: it holds a draft section and the whole stored
 * definition, and needs to know what the server will make of the section this one descends from.
 *
 * **EXPORTED SO IT CAN BE PINNED.** The authoring screen decides four things with it — the Retire /
 * Delete label on the section button, which confirmation dialog opens, whether the stage dropdown is
 * locked, and the sentence under the heading — and it had four subtly different in-component answers to
 * one question, of which three were wrong. A predicate that decides that much may not live where no test
 * can reach it.
 *
 * `plan_definition`'s expression is `answered_here & {f.key for f in previous.fields}` with
 * `answered_here = answered.get(previous.stage_key)`, and every part of that is load-bearing:
 *
 *  - **`previous`, so a section the payload names but the server has never seen is NOT answered.** That
 *    is `plan_definition`'s CREATE branch: no stored row, nothing to retire, no move to refuse. A new
 *    section whose key collides with an answered one is a different rule with its own 409 sentence.
 *  - **`previous.fields`, so a RETIRED field counts.** After any supersede or any removal of an answered
 *    question, a section's only answered key belongs to a field no form draws — which is why the
 *    editable projection cannot answer this at all.
 *  - **`previous.stage_key`, so the answers are read off the stage the section is stored under** and not
 *    off whichever stage the dropdown is currently showing. An unanswered section may be moved; asking
 *    this of the new stage would make a section stop being answered the moment somebody tried to move
 *    it, which is exactly when the answer matters.
 */
export function storedSectionHoldsAnswer(
  stored: readonly DwCustomSection[],
  sectionKey: string,
  answered: Readonly<Record<string, ReadonlySet<string>>>
): boolean {
  const previous = stored.find((candidate) => candidate.key === sectionKey);
  if (!previous) return false;
  return sectionHoldsAnswer(previous, answered[previous.stageKey] ?? new Set<string>());
}

/**
 * Whether this edit carries anything the designer must be told BEFORE they press Save — and nothing
 * wider than that.
 *
 * **IT IS NOT "HAS ANYTHING CHANGED", AND IT MUST NOT BE USED AS THE GATE ON A SAVE BUTTON.** Rule 2
 * on the server is that an answered question's help, required flag, unit, bounds and position are all
 * free to change — those five and no more; see {@link fieldEditCost} for what the type does, which is
 * not in that list — and none of the five appears in a diff, because none of them retires, deletes or
 * supersedes anything. A Save disabled on this predicate is a Save that refuses every ordinary edit
 * while the screen says "nothing has changed yet", which is a form the designer cannot correct a typo
 * in. What has changed is a comparison of {@link definitionBody} against the body of what is stored —
 * the bytes that will actually be sent.
 *
 * `retypedFields` IS counted, and it is the one entry here that the server does not convert. That is
 * the point of it: a conversion is reported by the server afterwards, in a count, while a retype is
 * accepted in silence and surfaces days later as a stage that will not submit. Suppressing the whole
 * "Saving will:" block for an edit whose only content is a retype would hide the one hazard on this
 * screen that nothing else in the system will ever mention.
 *
 * `movedSections` is deliberately not counted here: it is a REFUSAL rather than something a save will
 * do, it has its own sentence on screen in its own colour, and it blocks the save on its own.
 */
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

/**
 * A key suggested from a label, for a designer who should not have to invent one.
 *
 * IT IS OFFERED ONCE, FOR A NEW FIELD, AND NEVER RE-DERIVED. A key is permanent: the answers are
 * stored under it, and renaming one orphans every answer already given. So this may seed an empty box
 * and must never rewrite a key a designer has seen, let alone one that has been saved — a key that
 * followed the label would silently orphan the answers the moment somebody corrected a typo in the
 * question.
 */
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
  // The pattern demands a lower-case letter first, and a label that begins with a digit ("2-ply
  // count") would otherwise produce a key the server refuses with a message about a box the designer
  // never filled in.
  if (!/^[a-z]/.test(stem)) stem = `q${stem}`.slice(0, MAX_CUSTOM_KEY_CHARS);
  if (!CUSTOM_KEY_PATTERN.test(stem)) stem = "question";
  if (!taken.has(stem)) return stem;
  for (let n = 2; n < 1000; n += 1) {
    const candidate = `${stem.slice(0, MAX_CUSTOM_KEY_CHARS - 3)}${n}`;
    if (!taken.has(candidate)) return candidate;
  }
  return stem;
}

/** A blank field, ready to be typed into. Every key present, because the wire has every key. */
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

/** A blank section, ready to be typed into. */
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

/**
 * What one stored answer reads as on a printed line — for the read-only rendering of a retired field.
 *
 * An ENUM token falls back to the token itself when the option list no longer carries it, exactly as
 * `option_label` does on the server and `enum_label` does for the registry, and for their reason: a
 * draft written before an option was removed still holds that token, and printing it raw is better
 * than failing an export a designer is waiting on.
 */
export function customAnswerText(field: DwCustomField, value: DwValue | undefined): string {
  const label = (token: string) =>
    field.options.find((option) => option.value === token)?.label.trim() || token;
  if (value === null || value === undefined) return "";
  if (Array.isArray(value)) return value.map((item) => label(String(item))).join(", ");
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "object") return "";
  return label(String(value));
}
