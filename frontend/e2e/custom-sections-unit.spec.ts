import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  CUSTOM_ENTITY_KEY,
  CUSTOM_KEY_PATTERN,
  MAX_CUSTOM_DESCRIPTION_CHARS,
  MAX_CUSTOM_FIELDS_PER_SECTION,
  MAX_CUSTOM_HELP_CHARS,
  MAX_CUSTOM_KEY_CHARS,
  MAX_CUSTOM_LABEL_CHARS,
  MAX_CUSTOM_OPTIONS,
  MAX_CUSTOM_SECTIONS,
  MAX_CUSTOM_TITLE_CHARS,
  MAX_CUSTOM_UNIT_CHARS,
  V1_CUSTOM_TYPES,
  answeredCustomKeys,
  customDefinitionProblems,
  customFieldToDwField,
  customFieldsForStage,
  customSectionEntity,
  customSectionEntityKey,
  definitionBody,
  diffCustomDefinition,
  diffIsEmpty,
  editableSections,
  isV1CustomType,
  retiredFields,
  sectionsForStage,
  storedSectionHoldsAnswer,
  suggestCustomKey,
  type DwCustomDefinition,
  type DwCustomField,
  type DwCustomSection
} from "@/lib/customSections";
import { UNSUPPORTED_FIELD_TYPE_PREFIX, fieldTypeName, type DwFieldType } from "@/lib/designWorkshops";
import { buildStageEntries, emptyStage, scoreStageData, type DwDraft, type DwDraftStage } from "@/lib/designWorkshopStore";
import type { DwRegistry, DwStage } from "@/lib/designWorkshops";
import { workshopReadiness } from "@/lib/submissionReadiness";
import { buildWorkshopSearchIndex, readStageFocus, searchWorkshop, stageFieldHref } from "@/lib/workshopSearch";

/**
 * Designer-defined sections, on the browser's side of the wire — no backend, no credentials, no browser.
 *
 * WHY THIS SPEC NEEDS NEITHER A SERVER NOR A DATABASE, unlike most of this suite. Everything asserted
 * here is a pure function of a decoded payload or of a local draft: which entry a stage save sends, what
 * a required custom question does to a stage's arithmetic, which keys may be PUT back, and what an edit
 * will cost before it is made. There is no Postgres on this machine, so a round trip is not available —
 * and these are exactly the decisions a browser test would be the slowest possible way to check.
 * `ai-layers-unit.spec.ts` and `stage-entry-merge-unit.spec.ts` are the same shape for the same reason.
 *
 * ⚠ HOW `SERVER_FIELD` AND `SERVER_SECTION` WERE BUILT, STATED BECAUSE THE DIFFERENCE MATTERS. They were
 * transcribed key for key from `field_payload` and `section_payload` in
 * `backend/app/services/custom_sections.py` — every key, in those functions' own order — and NOT captured
 * from a running server, which this machine has no database for. So they pin that this client reads the
 * keys the SERVER'S CODE writes; they do not prove a deployed server writes them. A capture from a live
 * 200 would be strictly stronger and should replace these constants the first time one is available.
 *
 * THE DEFECT THIS SHAPE OF SPEC EXISTS FOR. `DwIdentityOcrResult` once declared `number`,
 * `documentType`, `name`, `confidence` and `message` — none of which that endpoint has ever sent. Decoding
 * JSON ignores unknown keys, so nothing threw: a PERFECT read of an identity card was reported to the
 * designer as unreadable, and Android's DTO carried the identical bug against the identical payload. For a
 * definition the same mistake reads as "this workshop has no custom questions", which is indistinguishable
 * from the truth for most workshops.
 */

/** One field exactly as `field_payload` serialises it. */
const SERVER_FIELD: DwCustomField = JSON.parse(`
{
  "id": "cmfield00000000000000001",
  "key": "loomCount",
  "label": "How many looms?",
  "type": "INT",
  "tier": "BASIC",
  "required": true,
  "help": "Count the working ones only.",
  "unit": "looms",
  "options": [],
  "maxLength": 0,
  "minValue": 0,
  "maxValue": 400,
  "sortOrder": 0,
  "retired": false,
  "supersededById": null
}
`);

/** One section exactly as `section_payload` serialises it. */
const SERVER_SECTION: DwCustomSection = JSON.parse(`
{
  "id": "cmsection000000000000001",
  "key": "loomShed",
  "stageKey": "CLUSTER_CRAFT_BACKGROUND",
  "title": "Loom shed",
  "description": "The shed behind the co-operative office.",
  "sortOrder": 0,
  "revision": 1,
  "retired": false,
  "fields": []
}
`);

function field(overrides: Partial<DwCustomField>): DwCustomField {
  return { ...SERVER_FIELD, ...overrides };
}

function section(overrides: Partial<DwCustomSection>): DwCustomSection {
  return { ...SERVER_SECTION, ...overrides };
}

function definition(sections: DwCustomSection[], version = "d1"): DwCustomDefinition {
  return { customSchemaVersion: version, sections, fetchedAt: "2026-08-12T09:00:00+00:00" };
}

/**
 * A miniature stage with one singleton and one collection, hand-built rather than fetched.
 *
 * The point of a pure module is that it can be checked without the 496-field registry being up, and a
 * fixture that changed when somebody edited stage 4 would make every failure here ambiguous.
 */
const STAGE: DwStage = {
  number: 4,
  key: "CLUSTER_CRAFT_BACKGROUND",
  title: "Cluster and craft background",
  purpose: "",
  notes: "",
  optionalStage: false,
  entities: [
    {
      key: "clusterBackground",
      name: "DwClusterBackground",
      cardinality: "SINGLETON",
      title: "Cluster background",
      description: "",
      parent: "",
      labelField: "",
      fields: [{ key: "artisanHouseholds", label: "Artisan households", type: "INT", tier: "BASIC", required: true }]
    },
    {
      key: "tool",
      name: "DwTool",
      cardinality: "COLLECTION",
      title: "Tools",
      description: "",
      parent: "",
      labelField: "name",
      fields: [{ key: "name", label: "Tool name", type: "TEXT", tier: "BASIC", required: true }]
    }
  ]
};

/** A stage this browser has never reconciled with the server — `serverLoadedAt` is null. */
function neverRead(overrides: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage(STAGE.key), ...overrides };
}

/** The same stage after the server's copy was folded in. */
function alreadyRead(overrides: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage(STAGE.key), serverLoadedAt: 1_700_000_000_000, ...overrides };
}

/** The one-stage registry, for the two walks that take one rather than a stage. */
const REGISTRY: DwRegistry = { version: "spec", enums: {}, stages: [STAGE] };

/**
 * A draft in the shape `designWorkshopStore` holds it, carrying a definition.
 *
 * `customDefinition` is on the DRAFT and not passed beside it, because that is where both walks under
 * test read it from — the readiness screen's address walk and the offline search index. A fixture that
 * handed it in separately would pin a call this application never makes.
 */
function draftWith(stages: Record<string, DwDraftStage>, customDefinition: DwCustomDefinition | null = null): DwDraft {
  return {
    schemaVersion: 2,
    localId: "dwlocal-spec",
    remoteId: null,
    header: { title: "Spec workshop", templateId: "DCH_STANDARD", status: "DRAFT" },
    headerDirtyAt: null,
    stages,
    createdAt: 0,
    updatedAt: 0,
    registryVersion: "spec",
    customSchemaVersion: customDefinition?.customSchemaVersion ?? "",
    customDefinition,
    ownerUserId: null,
    lastSyncedAt: null,
    failure: null
  } as unknown as DwDraft;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The contract
 * ──────────────────────────────────────────────────────────────────────────── */

test("the reserved key and the key pattern are the server's, character for character", () => {
  expect(CUSTOM_ENTITY_KEY).toBe("_custom");

  // `custom_sections.KEY_PATTERN` — lower-case letter first, letters and digits after, at most 40. The
  // leading lower-case is what makes a designer's key unable to collide with `_custom` or with the
  // protocol's own `_clientKey`/`_entryId`/`_ordinal`.
  expect(CUSTOM_KEY_PATTERN.test("loomCount")).toBe(true);
  expect(CUSTOM_KEY_PATTERN.test("a")).toBe(true);
  expect(CUSTOM_KEY_PATTERN.test("_custom")).toBe(false);
  expect(CUSTOM_KEY_PATTERN.test("Loom")).toBe(false);
  expect(CUSTOM_KEY_PATTERN.test("2ply")).toBe(false);
  expect(CUSTOM_KEY_PATTERN.test("loom_count")).toBe(false);
  expect(CUSTOM_KEY_PATTERN.test("a".repeat(41))).toBe(false);
});

test("exactly twelve types, and the ones v1 refuses are refused by NAME rather than by ignorance", () => {
  expect([...V1_CUSTOM_TYPES].sort()).toEqual(
    ["BOOL", "DATE", "DECIMAL", "ENUM", "INT", "LONG_TEXT", "MONEY", "MULTI_ENUM", "PERCENT", "TAGS", "TEXT", "TIME"]
  );
  /*
    THE FOUR THAT MATTER ARE TYPES THIS BUILD KNOWS PERFECTLY WELL. `FieldInput` has a real, working
    branch for every one of them, so "does the client have a branch" is the WRONG test — and it is the
    test the obvious implementation would have used. A custom IMAGE would sync as a `dwlocal:` reference
    that none of the five media walkers can resolve: the save reports success and the photograph is simply
    absent from the .docx. A custom REF would read FILLED on every form and UNFILLED in the document,
    because `ref_resolves` is supplied by the report and by nothing else.
  */
  for (const type of ["IMAGE", "REF", "RICH_TEXT", "GEO"]) expect(isV1CustomType(type)).toBe(false);
});

test("what comes back is not what may be sent: id, retired and supersededById never enter a PUT body", () => {
  /*
    `CustomFieldIn` is an `APIModel`, i.e. `extra="forbid"`, and declares none of the three — while
    `field_payload` emits all three on every field. A fetched definition therefore LOOKS like a sendable
    one and 422s on every field if it is spread into a request body, with a message about keys the designer
    never typed. This is the assertion that stops that being discovered in production.
  */
  const body = definitionBody([section({ fields: [field({})] })]);
  const sent = body.sections[0].fields[0] as Record<string, unknown>;
  expect(Object.keys(sent).sort()).toEqual(
    ["help", "key", "label", "maxLength", "maxValue", "minValue", "options", "required", "sortOrder", "tier", "type", "unit"]
  );
  expect(Object.keys(body.sections[0]).sort()).toEqual(
    ["description", "fields", "key", "sortOrder", "stageKey", "title"]
  );
  // The order is positional, not the stored number, so dragging a section up is what decides where it
  // prints — a definition whose every sortOrder was left at 0 would otherwise print in whatever order the
  // database handed the rows back, which is stable until it is not.
  const two = definitionBody([section({ key: "b", sortOrder: 9 }), section({ key: "a", sortOrder: 9 })]);
  expect(two.sections.map((entry) => [entry.key, entry.sortOrder])).toEqual([["b", 0], ["a", 1]]);
});

test("a retired field is never dropped from a read, and a retired SECTION retires its fields", () => {
  /*
    `fields_for` on the server forces every field of a retired section to `retired`, and reading it as
    "live sections only" was a silent data-loss bug there. The client can commit it independently: a
    section is retired precisely BECAUSE somebody answered it, so its keys are exactly the keys the
    `_custom` row still holds — left out of this list they become keys the definition does not carry, and
    the next ordinary save drops every one of them and reports them as `droppedCustomKeys`.
  */
  const live = field({ key: "a", retired: false });
  const gone = field({ key: "b", retired: true, sortOrder: 1 });
  const held = definition([section({ retired: true, fields: [live, gone] })]);

  const fields = customFieldsForStage(held, STAGE.key);
  expect(fields.map((entry) => entry.key)).toEqual(["a", "b"]);
  expect(fields.every((entry) => entry.retired)).toBe(true);
});

test("a RETIRED SECTION is never named in a PUT body, because naming one un-retires it", () => {
  /*
    `plan_definition` reads a named section as "ask this" and `apply_definition_plan` writes
    `isActive: True, retiredAt: None` for it — deliberately, so that re-adding a section a designer had
    removed is not a silent no-op. That cuts the other way for a client: retired sections arrive on
    every read (they must — their keys are the keys the `_custom` row still holds), so a screen that
    sent back what it fetched un-retired every retired section of the workshop on the next save of an
    unrelated question. The section starts being asked again, with no live fields, and the designer is
    told only that their new question was added.
  */
  const body = definitionBody([
    section({ key: "gone", retired: true, fields: [field({ key: "a", retired: true })] }),
    section({ key: "live", fields: [field({ key: "b" })] })
  ]);
  expect(body.sections.map((entry) => entry.key)).toEqual(["live"]);
  // And the survivor is renumbered from its own position, so the hole the retired one left is closed
  // rather than sent as a gap.
  expect(body.sections[0].sortOrder).toBe(0);
});

test("the field order in a body is the ARRAY's position, not the stored sortOrder", () => {
  /*
    THE FAILURE: the editor's "move this question up" swaps two array entries and leaves both stored
    numbers alone. Sorting by the stored number before assigning the positional one meant the position
    could never change anything — the screen showed the new order, the save reported success, and the
    form went on asking in the old one. Two questions left at `sortOrder: 0` were worse: they came back
    ordered by `localeCompare` on their keys, an order nobody chose.
  */
  const moved = definitionBody([
    section({
      fields: [
        field({ key: "second", label: "Second", sortOrder: 1 }),
        field({ key: "first", label: "First", sortOrder: 0 })
      ]
    })
  ]);
  expect(moved.sections[0].fields.map((entry) => [entry.key, entry.sortOrder])).toEqual([
    ["second", 0],
    ["first", 1]
  ]);
});

test("the editable projection drops what may not be typed into, and keeps everything else", () => {
  /*
    A retired question on screen as an editable row is a designer rewording evidence into a box whose
    every keystroke `definitionBody` then discards without a word — and a key box that unlocks because
    the row has no live stored twin. A retired SECTION offered as boxes is the un-retire above.
  */
  const held = [
    section({ key: "gone", retired: true, fields: [field({ key: "a", retired: true })] }),
    section({
      key: "live",
      fields: [field({ key: "b" }), field({ key: "c", retired: true, sortOrder: 1 })]
    })
  ];
  const editable = editableSections(held);
  expect(editable.map((entry) => entry.key)).toEqual(["live"]);
  expect(editable[0].fields.map((entry) => entry.key)).toEqual(["b"]);
  // The stored copy is untouched: it is what every edit's cost is decided against, and what tells the
  // designer why a retired key cannot be used again.
  expect(held[1].fields).toHaveLength(2);
});

test("an ordinary edit changes the BODY while the diff stays empty — which is why Save cannot gate on the diff", () => {
  /*
    Rule 2 on the server: everything about an answered question except its wording is free to change.
    None of it appears in a diff, because none of it retires, deletes or supersedes anything — so a Save
    disabled on an empty diff is a Save that refuses every ordinary edit while the screen says "nothing
    has changed yet", and a typo in a question can be corrected on screen and never saved. The editor
    therefore compares bodies; this pins the two halves of that argument at once.
  */
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?", help: "" })] })];
  const edited = [section({ fields: [field({ key: "loomCount", label: "How many looms?", help: "Working ones only." })] })];

  expect(diffIsEmpty(diffCustomDefinition(stored, edited, { [STAGE.key]: new Set(["loomCount"]) }))).toBe(true);
  expect(JSON.stringify(definitionBody(edited))).not.toBe(JSON.stringify(definitionBody(stored)));
  // Retitling a section, moving it and reordering its questions are the same class of change.
  expect(JSON.stringify(definitionBody([section({ title: "Loom shed (2026)" })]))).not.toBe(
    JSON.stringify(definitionBody([section({})]))
  );
});

test("a retired SECTION's fields read as retired to every reader, so no form can draw one as live", () => {
  /*
    `section_payload` reports each field's OWN flag, and a section's `isActive` is a different column —
    so a retired section can arrive carrying fields that call themselves live. `fields_for` forces the
    flag server-side for that reason. Forced at one door here as well: without it `customSectionEntity`
    drew a retired section as an ordinary editable form while the scorer counted none of its questions
    and `retiredFields` printed none of them either — a designer answering questions nobody is asking,
    into a bucket the completeness bar ignores.
  */
  const held = definition([section({ retired: true, fields: [field({ key: "a", retired: false })] })]);
  const [only] = sectionsForStage(held, STAGE.key);
  expect(only.fields.every((entry) => entry.retired)).toBe(true);
  expect(customSectionEntity(only).fields).toEqual([]);
  expect(retiredFields(only, { a: 12 }).map((entry) => entry.key)).toEqual(["a"]);
});

test("a retired field is PRINTED only where an answer stands against it", () => {
  const answered = field({ key: "kept", label: "How many looms?", retired: true });
  const never = field({ key: "blank", label: "How many dye baths?", retired: true, sortOrder: 1 });
  const block = section({ fields: [answered, never] });

  // A retired question nobody answered is one the designer removed and nobody filled in; drawing a
  // crossed-out row for it is noise. One with an answer is evidence recorded under a wording that appears
  // nowhere else, and hiding it is how two copies of one report disagree about the fieldwork.
  expect(retiredFields(block, { kept: 12 }).map((entry) => entry.key)).toEqual(["kept"]);
  expect(retiredFields(block, {}).map((entry) => entry.key)).toEqual([]);
  expect(retiredFields(block, { kept: "" }).map((entry) => entry.key)).toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The degrade
 * ──────────────────────────────────────────────────────────────────────────── */

test("a type outside the twelve is re-tokenised so no working control can be reached", () => {
  /*
    THE ASSERTION IS THAT THE TOKEN IS NOT A UNION MEMBER. `FieldInput` switches over `DwFieldType` with no
    `default:` for the registry's own 23 types, so a custom GEO passed through as "GEO" would be drawn as a
    working map card — for a value none of the five media walkers can see. Re-tokenising is what routes it
    to the read-only arm instead, and the raw token travels inside so the note can name it: a note that
    will not say what the type was is a note a designer cannot report.
  */
  for (const raw of ["GEO", "IMAGE", "REF", "RICH_TEXT", "WIDGET"]) {
    const adapted = customFieldToDwField(field({ type: raw }));
    expect(adapted.type).toBe(`${UNSUPPORTED_FIELD_TYPE_PREFIX}${raw}`);
    expect(fieldTypeName(adapted.type)).toBe(raw);
  }
  // And every one of the twelve passes through untouched, or the ordinary case would be drawn read-only.
  for (const type of V1_CUSTOM_TYPES) {
    expect(customFieldToDwField(field({ type })).type).toBe(type as DwFieldType);
  }
});

test("null bounds are dropped rather than passed through as null", () => {
  // `minValue`/`maxValue` are `number | null` on the wire and `number | undefined` on `DwField`. A `null`
  // reaching a `min` attribute renders as the string "null" on some engines, which silently makes every
  // number in the box invalid.
  const adapted = customFieldToDwField(field({ minValue: null, maxValue: null }));
  expect("minValue" in adapted).toBe(false);
  expect("maxValue" in adapted).toBe(false);
  expect(customFieldToDwField(field({ minValue: 0, maxValue: 400 }))).toMatchObject({ minValue: 0, maxValue: 400 });
});

test("the synthetic entity is per SECTION, carries only live fields, and is a singleton", () => {
  const entity = customSectionEntity(
    section({ fields: [field({ key: "a" }), field({ key: "b", retired: true, sortOrder: 1 })] })
  );
  // Per section and not `_custom`: two sections on one stage under one entity key would collide in the
  // `data-dw-field` anchors, in the `advanced-<key>` disclosure id, and in `findMissingViews`' lookup.
  expect(entity.key).toBe("_custom:loomShed");
  expect(entity.key).toBe(customSectionEntityKey(section({})));
  expect(entity.cardinality).toBe("SINGLETON");
  // A retired question is not offered again — that is what retiring means. Its answer is printed by the
  // other half of the screen.
  expect(entity.fields.map((entry) => entry.key)).toEqual(["a"]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The entry: the never-read rule
 * ──────────────────────────────────────────────────────────────────────────── */

test("a browser holding no custom answers sends NO _custom entry at all", () => {
  /*
    `plan_custom_write` treats "no entry" and "an entry carrying {}" as two different instructions: the
    first writes no row, the second is a designer clearing every answer and IS written. So `{}` from a
    browser that simply never fetched the definition would read on the server as "the designer cleared
    every custom answer" — and a stage save replaces the row wholesale and writes no `RecordRevision`, so
    the office's answers would be gone in place.
  */
  for (const stage of [neverRead(), alreadyRead(), neverRead({ custom: {} }), alreadyRead({ custom: {} })]) {
    expect(buildStageEntries(STAGE, stage).entries.filter((entry) => entry.entityKey === CUSTOM_ENTITY_KEY)).toEqual([]);
  }
});

test("a never-read stage with one custom answer sends it as a MERGE", () => {
  const { entries, rowKeys } = buildStageEntries(STAGE, neverRead({ custom: { loomCount: 12 } }));
  const custom = entries.filter((entry) => entry.entityKey === CUSTOM_ENTITY_KEY);
  // The whole entry, not just the flag: `merge` being true is half of what has to be true and `data`
  // carrying every key this browser holds is the other half.
  expect(custom).toEqual([{ entityKey: CUSTOM_ENTITY_KEY, data: { loomCount: 12 }, merge: true }]);
  // `rowKeys` has one slot per entry or every collection error index after this one decodes onto the
  // wrong row — the server keys per-field errors by an entry's index in the array that was sent.
  expect(rowKeys).toHaveLength(entries.length);
  expect(rowKeys[entries.indexOf(custom[0])]).toBeNull();
});

test("a stage this browser HAS read sends an empty container, because that is a designer clearing it", () => {
  // The half that stops the guard over-reaching. A browser that has seen the server's copy MEANS it when
  // it sends a bucket with a blanked key in it. `merge` is ABSENT, not false.
  expect(
    buildStageEntries(STAGE, alreadyRead({ custom: { loomCount: null } })).entries.filter(
      (entry) => entry.entityKey === CUSTOM_ENTITY_KEY
    )
  ).toEqual([{ entityKey: CUSTOM_ENTITY_KEY, data: { loomCount: null } }]);
});

test("no _custom entry ever carries merge:false", () => {
  /*
    The property a later simplification would break: `merge: neverRead` is the obvious tidy-up of the
    ternary, it is type-correct, it passes every other test in this file — and `APIModel` is
    `extra="forbid"`, so an API that predates the field would refuse EVERY stage save rather than only the
    never-downloaded ones. `e2e/stage-entry-merge-unit.spec.ts` pins the same property for the singleton
    arm; this is the custom arm's half of it.
  */
  const shapes: DwDraftStage[] = [
    neverRead({ custom: { loomCount: 1 } }),
    neverRead({ custom: { loomCount: null } }),
    alreadyRead({ custom: { loomCount: 1 } }),
    alreadyRead({ custom: {} }),
    alreadyRead({ custom: { loomCount: null }, collections: { tool: [{ name: "Pit loom" }] } })
  ];
  for (const stage of shapes) {
    for (const entry of buildStageEntries(STAGE, stage).entries) {
      expect(entry.merge === undefined || entry.merge === true).toBe(true);
    }
  }
});

test("a blank custom answer does not unlock the send on a never-read stage, and a zero does", () => {
  // `isFilled` is character-for-character the server's `_is_filled`. A form that rendered and banked an
  // empty box IS the never-downloaded case, and reading "" as an answer would put the deleting payload
  // back on the wire.
  for (const value of ["", "   ", null, undefined]) {
    expect(
      buildStageEntries(STAGE, neverRead({ custom: { loomCount: value as never } })).entries.filter(
        (entry) => entry.entityKey === CUSTOM_ENTITY_KEY
      )
    ).toEqual([]);
  }
  // And a zero IS an answer: a shed with no working looms recorded as 0 is a finding, and dropping it as
  // falsy would be the classic version of this mistake.
  expect(
    buildStageEntries(STAGE, neverRead({ custom: { loomCount: 0 } })).entries.filter(
      (entry) => entry.entityKey === CUSTOM_ENTITY_KEY
    )
  ).toEqual([{ entityKey: CUSTOM_ENTITY_KEY, data: { loomCount: 0 }, merge: true }]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The arithmetic
 * ──────────────────────────────────────────────────────────────────────────── */

test("a required custom question is counted BETWEEN the singleton and the collections, under the BARE label", () => {
  /*
    THE ORDER IS NOT COSMETIC. `missing` is printed in order — in full under the stage's progress bar, and
    truncated to three in the report's Outstanding column and on the phone's report screen — so whatever
    this list puts first is what a designer and a ministry officer actually read.

    THE BARE LABEL is what makes a duplicate label a definition-time refusal rather than a document
    disagreeing with itself: a collection field files `"Tools: Tool name"`, so it cannot collide, while two
    custom questions filing one string would collapse in the de-duplication while the count said two.
  */
  const score = scoreStageData(
    STAGE,
    {},
    { tool: [{ name: "" }] },
    [field({ key: "loomCount", label: "How many looms?", required: true })],
    {}
  );
  expect(score.missing).toEqual(["Artisan households", "How many looms?", "Tools: Tool name"]);
  expect(score.requiredTotal).toBe(3);
  expect(score.requiredFilled).toBe(0);
});

test("a retired custom question is skipped, so a corrected question cannot make a stage permanently incomplete", () => {
  const score = scoreStageData(
    STAGE,
    { artisanHouseholds: 412 },
    {},
    [field({ key: "old", label: "How many looms?", required: true, retired: true })],
    {}
  );
  expect(score.missing).toEqual([]);
  expect(score.requiredTotal).toBe(1);
  expect(score.isComplete).toBe(true);
});

test("the container is never tested as a whole: a bucket of blanks is not an answered stage", () => {
  /*
    `isFilled` returns true for ANY object with keys, so a bucket holding twenty blank answers is truthy. A
    scorer that tested the container would report a stage as complete on the strength of a form having been
    rendered on it.
  */
  const fields = [
    field({ key: "a", label: "A", required: true }),
    field({ key: "b", label: "B", required: true, sortOrder: 1 })
  ];
  const score = scoreStageData(STAGE, { artisanHouseholds: 1 }, {}, fields, { a: "", b: null });
  expect(score.missing).toEqual(["A", "B"]);
  expect(score.requiredFilled).toBe(1);
  expect(score.requiredTotal).toBe(3);
});

test("an optional custom question counts towards the optional tally and never blocks", () => {
  const score = scoreStageData(
    STAGE,
    { artisanHouseholds: 1 },
    {},
    [field({ key: "note", label: "Anything else?", required: false, tier: "STANDARD" })],
    { note: "The shed floods in July." }
  );
  expect(score.missing).toEqual([]);
  expect(score.optionalTotal).toBe(1);
  expect(score.optionalFilled).toBe(1);
  expect(score.isComplete).toBe(true);
});

test("two custom questions sharing a label collapse in `missing` while the count says two — which is why the server refuses it", () => {
  /*
    THE DEFECT THIS DOCUMENTS IS ALREADY SHIPPED ONCE (144/144 printed beside "Not recorded." thirty-six
    times). `missing` is de-duplicated the way `dict.fromkeys` does it server-side, while `requiredTotal`
    counts every field — so the readiness screen shows one row and the arithmetic beside it says two. This
    test asserts the collapse EXISTS rather than that it is desirable: it is why a duplicate label is a
    definition-time 422 and why `customDefinitionProblems` refuses one before the round trip.
  */
  const fields = [
    field({ key: "a", label: "Notes", required: true }),
    field({ key: "b", label: "Notes", required: true, sortOrder: 1 })
  ];
  const score = scoreStageData(STAGE, { artisanHouseholds: 1 }, {}, fields, {});
  expect(score.missing).toEqual(["Notes"]);
  expect(score.requiredTotal).toBe(3);

  expect(
    customDefinitionProblems([
      section({ fields: [field({ key: "a", label: "Notes", required: true }), field({ key: "b", label: "Notes", required: true })] })
    ]).some((problem) => problem.includes("both called"))
  ).toBe(true);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The rules that must reach the screen
 * ──────────────────────────────────────────────────────────────────────────── */

test("only a BASIC question may be required, and the refusal says why", () => {
  const problems = customDefinitionProblems([
    section({ fields: [field({ key: "a", label: "A", required: true, tier: "ADVANCED" })] })
  ]);
  expect(problems.some((problem) => problem.includes("Only a Basic question may be required"))).toBe(true);
});

test("a choice needs two options, and a non-choice may not carry any", () => {
  expect(
    customDefinitionProblems([
      section({ fields: [field({ key: "a", label: "A", type: "ENUM", required: false, tier: "STANDARD", options: [{ value: "ONE", label: "" }] })] })
    ]).some((problem) => problem.includes("A choice needs at least two"))
  ).toBe(true);

  expect(
    customDefinitionProblems([
      section({
        fields: [field({ key: "a", label: "A", type: "TEXT", required: false, tier: "STANDARD", options: [{ value: "ONE", label: "" }], minValue: null, maxValue: null })]
      })
    ]).some((problem) => problem.includes("cannot carry options"))
  ).toBe(true);
});

test("a bound that would never be checked is refused rather than stored and ignored", () => {
  // `_range_checked` is only reached from the numeric branches of `coerce_value`, so a `minValue` on a TEXT
  // field is an inert control — the stored-and-ignored setting this repository has already had to go back
  // and fix seven of.
  expect(
    customDefinitionProblems([
      section({ fields: [field({ key: "a", label: "A", type: "TEXT", required: false, tier: "STANDARD", minValue: 1, maxValue: null })] })
    ]).some((problem) => problem.includes("would never be checked"))
  ).toBe(true);
});

test("a sound definition produces no problems at all", () => {
  expect(
    customDefinitionProblems([
      section({
        fields: [
          field({ key: "loomCount", label: "How many looms?", required: true, tier: "BASIC" }),
          field({
            key: "dyeBath",
            label: "Which dye bath?",
            type: "ENUM",
            tier: "STANDARD",
            required: false,
            sortOrder: 1,
            minValue: null,
            maxValue: null,
            options: [
              { value: "INDIGO", label: "Indigo" },
              { value: "MADDER", label: "" }
            ]
          })
        ]
      })
    ])
  ).toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * What an edit costs, said before it is made
 * ──────────────────────────────────────────────────────────────────────────── */

test("rewording an ANSWERED question is predicted as a supersede, and an unanswered one is free", () => {
  /*
    THE TWELVE-WEAVERS RULE. "How many looms?" answered "12", reworded to "How many weavers?", and a
    ministry report now states there are twelve weavers. The server does not REFUSE the rewording — it
    converts it — so a designer who is only told afterwards has been overruled by a machine on a screen
    about their own instrument, and the next thing they do is press the button again.
  */
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?" })] })];
  const next = [section({ fields: [field({ key: "loomCount", label: "How many weavers?" })] })];

  const answered = diffCustomDefinition(stored, next, { [STAGE.key]: new Set(["loomCount"]) });
  expect(answered.superseded).toEqual([{ was: "How many looms?", now: "How many weavers?" }]);
  expect(answered.created).toBe(0);

  const untouched = diffCustomDefinition(stored, next, { [STAGE.key]: new Set() });
  expect(untouched.superseded).toEqual([]);
  expect(untouched.created).toBe(0);
});

test("rule 2's list is the server's own five, and a TYPE change is not one of them", () => {
  /*
    RULE 2, READ OFF `custom_sections.py` RATHER THAN REMEMBERED: *"A field with answers may freely
    change its help, its required flag, its unit, its bounds and its position."* The module docstring
    says exactly that and `_plan_fields`' own RULE 2 comment repeats the same five. **The type is in
    neither list**, and this client used to add it in four places — a comment, this spec, the editor's
    note, and a sentence shown to the designer.
  */
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?", help: "", required: false })] })];
  const next = [
    section({ fields: [field({ key: "loomCount", label: "How many looms?", help: "Working ones only.", required: true, maxValue: 40 })] })
  ];
  expect(diffCustomDefinition(stored, next, { [STAGE.key]: new Set(["loomCount"]) })).toMatchObject({
    superseded: [],
    created: 0,
    retiredFields: [],
    deletedFields: [],
    retypedFields: []
  });
});

test("changing the TYPE of an answered question is named as a hazard, because the server neither refuses nor converts it", () => {
  /*
    WHAT THE SERVER ACTUALLY DOES, established by reading it rather than by assuming a symmetry with the
    label rule. `_plan_fields` plans a plain EDIT for a type change — there is no branch that refuses one
    and none that supersedes one — so the PUT is ACCEPTED and the stored answer stays in the container
    under the shape the old type wrote it in.

    THE BILL ARRIVES ON THE ANSWER PATH, one save later and on a different screen. `plan_custom_write`'s
    phase one re-coerces every key the client sends, and the client sends the stored answer back because
    the form drew it: `coerce_value` on `int("about nine")` raises, and is caught as
    *"How many looms? is not a valid int"* — a refusal naming a value nobody typed on this visit. The
    rejected-value loop then writes the old answer back, so nothing is lost, and under `submit` that same
    error 422s the WHOLE STAGE. `plan_custom_write`'s docstring names this exact failure for a BOUNDS
    change and calls the stage "permanently unsubmittable".

    So the honest prediction is neither FREE nor SUPERSEDE: nothing is converted, and the diff must not
    claim one — but the designer has to be told before they press Save, which is this list's whole job.
  */
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?", type: "TEXT" })] })];
  const next = [section({ fields: [field({ key: "loomCount", label: "How many looms?", type: "INT" })] })];

  const diff = diffCustomDefinition(stored, next, { [STAGE.key]: new Set(["loomCount"]) });
  expect(diff.retypedFields).toEqual([{ label: "How many looms?", was: "TEXT", now: "INT" }]);
  // NOT a supersede and NOT a retire: the server converts nothing, and predicting a conversion it does
  // not perform would be the same lie told the other way round.
  expect(diff.superseded).toEqual([]);
  expect(diff.retiredFields).toEqual([]);
  // It is a warning worth drawing, so the "Saving will:" block must not be suppressed by it.
  expect(diffIsEmpty(diff)).toBe(false);

  // Unanswered, there is no evidence to strand and the change is genuinely free.
  expect(diffCustomDefinition(stored, next, { [STAGE.key]: new Set() }).retypedFields).toEqual([]);
});

test("removing a question is a RETIRE when it has answers and a DELETE when it does not", () => {
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?" })] })];
  const next = [section({ fields: [] })];

  expect(diffCustomDefinition(stored, next, { [STAGE.key]: new Set(["loomCount"]) })).toMatchObject({
    retiredFields: ["How many looms?"],
    deletedFields: []
  });
  expect(diffCustomDefinition(stored, next, { [STAGE.key]: new Set() })).toMatchObject({
    retiredFields: [],
    deletedFields: ["How many looms?"]
  });
});

test("a whole section is retired when anything under it has been answered, and deleted when nothing has", () => {
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?" })] })];

  expect(diffCustomDefinition(stored, [], { [STAGE.key]: new Set(["loomCount"]) })).toMatchObject({
    retiredSections: ["Loom shed"],
    deletedSections: []
  });
  expect(diffCustomDefinition(stored, [], { [STAGE.key]: new Set() })).toMatchObject({
    retiredSections: [],
    deletedSections: ["Loom shed"]
  });
});

test("moving an ANSWERED section is named as the one edit the server refuses outright", () => {
  /*
    The answers live in the container of the stage the section is asked at, so moving it would leave them
    behind in the old stage's row: still stored, no longer asked, no longer scored, invisible on every
    form. That is not an edit, it is a silent data loss, so the server raises a 409 — and the editor has to
    say so before the button is pressed rather than after.
  */
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?" })] })];
  const moved = [section({ stageKey: "SKETCH_DEVELOPMENT", fields: [field({ key: "loomCount", label: "How many looms?" })] })];

  expect(diffCustomDefinition(stored, moved, { [STAGE.key]: new Set(["loomCount"]) }).movedSections).toEqual(["Loom shed"]);
  // Unanswered, it is an ordinary edit and nothing is at stake.
  expect(diffCustomDefinition(stored, moved, { [STAGE.key]: new Set() }).movedSections).toEqual([]);
});

test("the readiness link for a custom question survives the round trip to the stage form", () => {
  /*
    THE TWO ENDS ARE SPELLED IN TWO FILES AND MUST AGREE. `lib/submissionReadiness` builds the link from
    `customSectionEntityKey`; the stage form renders the section under `customSectionEntity`, and
    `FieldCell` highlights on a string comparison of the two. `fieldFocusKey` joins them with a dot and
    `readStageFocus` splits on the FIRST dot — which is safe here only because a section key can carry no
    dot (the key pattern allows letters and digits) and the reserved prefix uses a colon. If either ever
    changed, the link would land on the right stage and highlight nothing, which reads exactly like a
    stale result rather than like a broken link.

    ⚠ THIS TEST USED TO BUILD THE HREF ITSELF, and so asserted nothing about the code that builds it in
    production: the whole custom half of `stageAddresses` could be deleted and it still passed. It now
    goes through `workshopReadiness`, which is the only exported door to that walk — `stageAddresses` is
    private, deliberately, because a readiness address is meaningless outside the scored stage it
    belongs to. Deleting the custom block inside it now fails on the FIRST expectation below.
  */
  const block = section({ key: "loomShed", fields: [field({ key: "loomCount", label: "How many looms?", required: true })] });
  const draft = draftWith(
    { [STAGE.key]: alreadyRead({ singletons: { clusterBackground: { artisanHouseholds: 412 } } }) },
    definition([block])
  );

  const readiness = workshopReadiness(REGISTRY, draft, "cmws01");
  const item = readiness.blocking.find((entry) => entry.label === "How many looms?");
  // The required custom question BLOCKS — that half comes from the scorer and would survive the walk
  // being deleted, so it is asserted separately from the address that would not.
  expect(item, "a required custom question must reach the readiness screen at all").toBeTruthy();
  expect(item?.address).toMatchObject({
    entityKey: customSectionEntityKey(block),
    fieldKey: "loomCount",
    anchorFieldKey: "loomCount",
    entityTitle: "Loom shed",
    rowKey: null
  });

  // …and the link that address produces resolves, on arrival, to the entity the form renders under.
  const focus = readStageFocus(new URLSearchParams((item?.href ?? "").split("?")[1]));
  expect(focus).toEqual({ entityKey: customSectionEntityKey(block), fieldKey: "loomCount", rowKey: null });
  expect(focus?.entityKey).toBe(customSectionEntity(block).key);
  // A hand-built href and the real one are the same string, which is what makes the two ends one rule.
  expect(item?.href).toBe(stageFieldHref("cmws01", STAGE.key, customSectionEntityKey(block), "loomCount", null));
});

test("an ANSWERED custom question is not addressed as missing, so the walk cannot invent a blocker", () => {
  // The other half of the same walk: `stageAddresses` reads the stage's own `custom` bucket through
  // `isFilled`, so a question that has been answered produces no item at all.
  const block = section({ key: "loomShed", fields: [field({ key: "loomCount", label: "How many looms?", required: true })] });
  const draft = draftWith(
    {
      [STAGE.key]: alreadyRead({
        singletons: { clusterBackground: { artisanHouseholds: 412 } },
        custom: { loomCount: 12 }
      })
    },
    definition([block])
  );
  expect(workshopReadiness(REGISTRY, draft, "cmws01").blocking.map((entry) => entry.label)).toEqual([]);
});

test("a suggested key is a legal key, is offered once, and never collides", () => {
  expect(suggestCustomKey("How many looms?", new Set())).toBe("howManyLooms");
  // A label starting with a digit would otherwise produce a key the server refuses with a message about a
  // box the designer never filled in.
  expect(CUSTOM_KEY_PATTERN.test(suggestCustomKey("2-ply count", new Set()))).toBe(true);
  expect(CUSTOM_KEY_PATTERN.test(suggestCustomKey("…", new Set()))).toBe(true);
  expect(suggestCustomKey("How many looms?", new Set(["howManyLooms"]))).not.toBe("howManyLooms");
  expect(CUSTOM_KEY_PATTERN.test(suggestCustomKey("a".repeat(80), new Set()))).toBe(true);
});

test("a section is retired rather than deleted when its ONLY answered key belongs to a RETIRED field", () => {
  /*
    THE CLIENT ASKED A NARROWER QUESTION THAN THE SERVER AND SO PREDICTED THE OPPOSITE ANSWER.

    `plan_definition` tests `answered_here & {f.key for f in previous.fields}` — EVERY field of the
    section, retired ones included — and `_keys_this_put_keeps` tests the same set for the same reason.
    This side tested `liveFields(previous)`, which is exactly the set that excludes the field holding the
    answer. The state is the ordinary one after a supersede or a removal: "How many looms?" was answered
    "12" and retired, and the questions still being asked have never been filled in.

    What the divergence costs: the screen offers to DELETE the section and promises nothing is lost, the
    server RETIRES it and keeps every row, and the designer is told afterwards — in a count — that a
    section they were told would vanish is still there. It also makes `looms` unusable as a key while the
    screen has just said the question holding it was deleted.
  */
  const stored = [
    section({
      fields: [
        field({ key: "loomCount", label: "How many looms?", retired: true }),
        field({ key: "dyeBath", label: "Which dye bath?", sortOrder: 1 })
      ]
    })
  ];
  const answered = { [STAGE.key]: new Set(["loomCount"]) };

  expect(diffCustomDefinition(stored, [], answered)).toMatchObject({
    retiredSections: ["Loom shed"],
    deletedSections: []
  });

  // The move refusal is the same test on the server (`answered_here & {f.key for f in previous.fields}`)
  // and was wrong here in the same way: this section cannot be moved, because its answers are stored with
  // the stage they were asked at whether the question that took them is still asked or not.
  const moved = [section({ stageKey: "SKETCH_DEVELOPMENT", fields: stored[0].fields })];
  expect(diffCustomDefinition(stored, moved, answered).movedSections).toEqual(["Loom shed"]);
});

test("the authoring screen asks `plan_definition`'s question, not the one its own boxes can answer", () => {
  /*
    THE PREDICATE FOUR CONTROLS ARE DECIDED BY: the section's Retire-or-Delete label, which confirmation
    dialog opens, whether the stage dropdown is locked, and the sentence under the heading. The editor had
    four separate in-component answers to it and three were the narrow one — `liveFields` over the EDITABLE
    projection, which carries no retired field at all — so a section whose only answered question had since
    been retired offered a button reading "Delete" over a dialog reading "Retire this section?", above a
    heading promising everything was still freely editable, with an unlocked stage dropdown.

    `plan_definition` asks `answered_here & {f.key for f in previous.fields}` where
    `answered_here = answered.get(previous.stage_key)`, and the three assertions below are its three halves.
    Each one fails against one of the versions this screen actually shipped.
  */
  const retiredHolder = section({
    key: "loomShed",
    fields: [
      field({ key: "loomCount", label: "How many looms?", retired: true }),
      field({ key: "dyeBath", label: "Which dye bath?", sortOrder: 1 })
    ]
  });
  const answered = { [STAGE.key]: new Set(["loomCount"]) };

  // 1. EVERY field, retired ones included. The `liveFields` version answered false here.
  expect(storedSectionHoldsAnswer([retiredHolder], "loomShed", answered)).toBe(true);

  // 2. The STORED section's stage, never the one being typed into. A draft that has moved the section to
  //    another stage must not stop it being answered — that is precisely the edit the server refuses, and
  //    the version that read the draft's own `stageKey` emptied the answer set the moment it was tried.
  expect(storedSectionHoldsAnswer([retiredHolder], "loomShed", { SKETCH_DEVELOPMENT: new Set(["loomCount"]) })).toBe(
    false
  );
  expect(storedSectionHoldsAnswer([section({ ...retiredHolder, stageKey: "SKETCH_DEVELOPMENT" })], "loomShed", answered)).toBe(
    false
  );

  // 3. A section the server has never seen is not answered, whatever its keys collide with. That is
  //    `plan_definition`'s CREATE branch: no stored row, so nothing to retire and no move to refuse.
  expect(storedSectionHoldsAnswer([], "loomShed", answered)).toBe(false);
  expect(storedSectionHoldsAnswer([retiredHolder], "shedSurvey", answered)).toBe(false);

  // And the ordinary negative: a stored section nobody has answered anywhere.
  expect(storedSectionHoldsAnswer([retiredHolder], "loomShed", { [STAGE.key]: new Set() })).toBe(false);

  // The prediction the same screen prints beside those controls is the same expression, so the two cannot
  // say opposite things about one section — which is the state the narrow test put them in.
  expect(diffCustomDefinition([retiredHolder], [], answered)).toMatchObject({
    retiredSections: ["Loom shed"],
    deletedSections: []
  });
});

test("a key this DEVICE still holds an unsent answer under is answered, even when the server has never seen it", () => {
  /*
    THE DEFECT: the editor built its whole `answered` map from `getDesignWorkshop(...)` — the SERVER's
    buckets — while the answer that decides retire-versus-delete may be sitting in this browser's outbox.
    A designer answers a custom question over a fortnight in a cluster; the stage cannot sync because a
    photograph on it is still uploading, so the pass holds the whole stage back. Back on wifi they open
    this editor, and the button says Delete under a dialog reading "Nobody has answered it, so nothing is
    lost." The PUT deletes the field row; the photograph finishes; the stage syncs; the server drops the
    key as unknown and writes the row without it — and the sync pass reports success, because it reads
    `saved.errors` and nothing else.

    So "answered" has to mean "answered anywhere this device can see", which is the union of the two
    buckets and never the server's alone.
  */
  const fields = [field({ key: "loomCount", label: "How many looms?" })];

  // The server has the answer: the case that already worked.
  expect([...answeredCustomKeys(fields, { loomCount: 12 })]).toEqual(["loomCount"]);
  // Only this device has it — unsent, and every bit as much an answer.
  expect([...answeredCustomKeys(fields, {}, { loomCount: 12 })]).toEqual(["loomCount"]);
  expect([...answeredCustomKeys(fields, undefined, { loomCount: 12 })]).toEqual(["loomCount"]);
  // Neither holds one, and a blank in either is still a blank — `isFilled` decides, not presence.
  expect([...answeredCustomKeys(fields, {}, {})]).toEqual([]);
  expect([...answeredCustomKeys(fields, { loomCount: "" }, { loomCount: "   " })]).toEqual([]);
  // A local edit that CLEARED a key the server still holds leaves it answered: the server's row is what
  // the definition edit is applied against, and it is still standing.
  expect([...answeredCustomKeys(fields, { loomCount: 12 }, {})]).toEqual(["loomCount"]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Findable: the offline index
 * ──────────────────────────────────────────────────────────────────────────── */

test("a custom answer is searchable, under the section the form renders it in", () => {
  /*
    THE SENTENCE THAT MADE THIS A DEFECT RATHER THAN A GAP: the panel prints *"Nothing in this workshop
    holds that word. Every answer saved on this device was searched — 1,393 of them."* A designer who
    recorded "Indigo, unbleached" under their own question, and searches "indigo" with no signal to find
    where they put it, is told in as many words that it is not there. It is on the stage form, inside the
    percentage on the same screen, and in the .docx.
  */
  const block = section({
    key: "dyeShed",
    title: "Dye shed",
    fields: [field({ key: "dyeBath", label: "Dye bath", type: "TEXT", required: false })]
  });
  const draft = draftWith({ [STAGE.key]: alreadyRead({ custom: { dyeBath: "Indigo, unbleached" } }) }, definition([block]));

  const index = buildWorkshopSearchIndex(REGISTRY, draft);
  const [hit, ...rest] = searchWorkshop(index, "indigo").hits;
  expect(rest).toEqual([]);
  expect(hit).toMatchObject({
    stageKey: STAGE.key,
    // The SECTION's rendering identity, so the hit's link highlights the box rather than landing on the
    // stage with nothing marked — the same string `customSectionEntity` renders under.
    entityKey: customSectionEntityKey(block),
    entityTitle: "Dye shed",
    fieldKey: "dyeBath",
    fieldLabel: "Dye bath",
    anchorFieldKey: "dyeBath",
    recordTitle: null,
    rowKey: null
  });
  expect(hit.snippet.segments.map((segment) => segment.text).join("")).toContain("Indigo, unbleached");
  // The anchor resolves to the entity the stage form actually draws the section under. Two files spell
  // this string and a divergence costs a highlight rather than an error, which is why it is asserted.
  expect(hit.entityKey).toBe(customSectionEntity(block).key);
});

test("a stage whose ONLY answers are custom counts towards the coverage figure the panel prints", () => {
  // `stagesWithText` is what the panel turns into "across N stages", and a stage absent from it makes the
  // panel under-report how much was searched — beside a sentence claiming everything was.
  const block = section({ key: "dyeShed", fields: [field({ key: "dyeBath", label: "Dye bath", type: "TEXT", required: false })] });
  const index = buildWorkshopSearchIndex(
    REGISTRY,
    draftWith({ [STAGE.key]: alreadyRead({ custom: { dyeBath: "Indigo" } }) }, definition([block]))
  );
  expect(index.stageCount).toBe(1);
  expect(index.fieldCount).toBe(1);
});

test("the index carries a custom ENUM by its label and a retired question by nothing at all", () => {
  const block = section({
    key: "dyeShed",
    fields: [
      field({
        key: "dyeBath",
        label: "Dye bath",
        type: "ENUM",
        required: false,
        options: [{ value: "INDIGO", label: "Indigo vat" }, { value: "MADDER", label: "" }]
      }),
      field({ key: "oldNote", label: "Old note", type: "TEXT", required: false, retired: true, sortOrder: 1 })
    ]
  });
  const draft = draftWith(
    // "kusuma" appears in the retired answer and nowhere else, so the assertion below cannot pass by
    // accident on a word the live question also happens to carry.
    { [STAGE.key]: alreadyRead({ custom: { dyeBath: "INDIGO", oldNote: "Kusuma dyeing, in the shared shed." } }) },
    definition([block])
  );
  const index = buildWorkshopSearchIndex(REGISTRY, draft);

  // The token is not what a designer typed or reads: the option's own label is, exactly as `enumLabel`
  // resolves a registry enum. A hit whose snippet is `INDIGO` is a hit quoting a storage format.
  expect(searchWorkshop(index, "indigo").hits.map((entry) => entry.fieldKey)).toEqual(["dyeBath"]);
  expect(index.entries.find((entry) => entry.fieldKey === "dyeBath")?.text).toBe("Indigo vat");

  /*
    A RETIRED QUESTION'S ANSWER IS NOT INDEXED, and that is the same rule this index already applies to a
    deprecated registry field rather than a new exception. `CustomSections.tsx` prints it through
    `RetiredAnswer`, which is deliberately text and not a control — so it carries no `data-dw-field`
    wrapper, and `readStageFocus` has nothing to resolve against. Decision 4 in the module header: a
    result a designer cannot be taken to is not a result.
  */
  expect(searchWorkshop(index, "kusuma").hits).toEqual([]);
  // …and it is absent from the index itself, not merely unranked.
  expect(index.entries.map((entry) => entry.fieldKey)).toEqual(["dyeBath"]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The bounds, pinned against the service that owns them
 * ──────────────────────────────────────────────────────────────────────────── */

/** The server's own source, read off disk. `derived-fields-unit.spec.ts` pins a claim the same way. */
function backendSource(...parts: string[]): string {
  return readFileSync(join(__dirname, "..", "..", "backend", "app", ...parts), "utf8");
}

/** One `NAME = 12` assignment out of a Python module, as a number. */
function pythonInt(source: string, name: string): number {
  const match = new RegExp(`^${name}\\s*=\\s*(\\d+)\\s*$`, "m").exec(source);
  expect(match, `${name} is no longer declared where this spec reads it`).toBeTruthy();
  return Number(match?.[1]);
}

test("the nine bounds are the service's own values, read off the Python rather than remembered", () => {
  /*
    THE COMMENT ABOVE THESE CONSTANTS PROMISED THIS SPEC PINNED THEM AND NO SPEC NAMED ONE OF THEM. That
    is the defect this repository files most often — a comment claiming a guard that does not exist — and
    it is worse than silence, because the next person to move a bound reads it and believes the drift
    will be caught.

    A bound this file and the server disagreed about is a 422 saying something the screen's own character
    counter contradicts: the box stops at 160, the designer cannot type the character that would explain
    the refusal, and the only way out of the screen is to guess.

    Read out of the SOURCE rather than requested from a running server, on purpose and with the limit
    stated: this machine has no database, so a round trip is not available, and the constants are
    module-level literals that a text read answers exactly. It pins this client against the service's
    code; it does not prove what a deployed server is running.
  */
  const service = backendSource("services", "custom_sections.py");
  const declared: Record<string, number> = {
    MAX_CUSTOM_SECTIONS,
    MAX_CUSTOM_FIELDS_PER_SECTION,
    MAX_CUSTOM_OPTIONS,
    MAX_CUSTOM_LABEL_CHARS,
    MAX_CUSTOM_HELP_CHARS,
    MAX_CUSTOM_TITLE_CHARS,
    MAX_CUSTOM_DESCRIPTION_CHARS,
    MAX_CUSTOM_KEY_CHARS,
    MAX_CUSTOM_UNIT_CHARS
  };
  for (const [name, held] of Object.entries(declared)) {
    expect(pythonInt(service, name), `${name} has drifted from the service`).toBe(held);
  }
  // Nine, and the count is asserted so a TENTH bound added on the server cannot be silently unmirrored.
  expect(Object.keys(declared)).toHaveLength(9);
  expect(service.match(/^MAX_CUSTOM_[A-Z_]+\s*=\s*\d+\s*$/gm) ?? []).toHaveLength(9);

  // The key pattern is a bound too, and the one whose drift is silent: a client regex that accepted more
  // than the server's would send keys refused one round trip later, under a message about a box that
  // looks correct.
  expect(/\^\[a-z\]\[A-Za-z0-9\]\{0,39\}\$/.test(service)).toBe(true);
  expect(CUSTOM_KEY_PATTERN.source).toBe("^[a-z][A-Za-z0-9]{0,39}$");
});

test("an option's own two bounds are refused here, because they live only in the envelope", () => {
  /*
    `customDefinitionProblems` mirrors every bound `validate_definition` declares — and the two on an
    OPTION are not declared there. They are in the pydantic envelope, `CustomOptionIn`, and nothing in
    `services/custom_sections.py` mentions either: so they arrived as a 422 this editor never predicted,
    from a list a designer built one line at a time in a textarea with no counter on it.
  */
  const schema = backendSource("schemas", "design_workshops.py");
  const envelope = schema.slice(schema.indexOf("class CustomOptionIn"), schema.indexOf("class CustomFieldIn"));
  const valueChars = Number(/value:\s*str\s*=\s*Field\(min_length=1,\s*max_length=(\d+)\)/.exec(envelope)?.[1]);
  expect(valueChars, "CustomOptionIn.value no longer declares its bound where this spec reads it").toBe(64);
  // The label's bound is `MAX_CUSTOM_LABEL_CHARS`, shared with a field's label — imported rather than
  // repeated on that side, which is why only the value's 64 is a number of its own.
  expect(/label:\s*str\s*=\s*Field\(default="",\s*max_length=MAX_CUSTOM_LABEL_CHARS\)/.test(envelope)).toBe(true);

  const tooLong = customDefinitionProblems([
    section({
      fields: [
        field({
          key: "dyeBath",
          label: "Which dye bath?",
          type: "ENUM",
          required: false,
          tier: "STANDARD",
          minValue: null,
          maxValue: null,
          options: [
            { value: "A".repeat(valueChars + 1), label: "Fine" },
            { value: "MADDER", label: "L".repeat(MAX_CUSTOM_LABEL_CHARS + 1) }
          ]
        })
      ]
    })
  ]);
  expect(tooLong.some((problem) => problem.includes("option value")), tooLong.join(" | ")).toBe(true);
  expect(tooLong.some((problem) => problem.includes("printed form")), tooLong.join(" | ")).toBe(true);

  // And a list that sits exactly on both bounds is accepted, or the check would be a new refusal of its
  // own rather than a mirror — the line this repository draws for every client-side check.
  expect(
    customDefinitionProblems([
      section({
        fields: [
          field({
            key: "dyeBath",
            label: "Which dye bath?",
            type: "ENUM",
            required: false,
            tier: "STANDARD",
            minValue: null,
            maxValue: null,
            options: [
              { value: "A".repeat(valueChars), label: "L".repeat(MAX_CUSTOM_LABEL_CHARS) },
              { value: "MADDER", label: "" }
            ]
          })
        ]
      })
    ])
  ).toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The two STAGE-SCOPED collisions — the half of the mirror that was missing
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Audit 2026-08-15 (MINOR, frontend): "the definition editor's pre-flight validator omits both
 * stage-scoped collision checks the server enforces, so Save is offered for a definition the server
 * refuses outright". `customDefinitionProblems` took only `sections`, so neither it nor
 * `fieldProblems` had a stage to check against — `problems` was empty for a colliding definition and
 * the Save gate (`disabled={busy || problems.length > 0 || …}`) was open. A whole-set PUT was then
 * refused after the fact for a rule this editor exists to catch before it, taking every unrelated
 * edit in the same body down with it.
 *
 * These pin all three halves: that each check fires, that the NARROWING of each is respected, and
 * that no registry means silence rather than a refusal.
 */

test("a custom key equal to a live registry field of that stage is refused before the round trip", () => {
  const problems = customDefinitionProblems(
    [section({ fields: [field({ key: "artisanHouseholds", label: "How many households?", required: true })] })],
    [STAGE]
  );
  expect(problems.some((problem) => problem.includes("is already a field of stage 4"))).toBe(true);
});

test("the key check walks EVERY entity of the stage, collections included", () => {
  // `name` is a field of the `tool` COLLECTION, not of the singleton. The server walks
  // `for entity in stage.entities` and so must this: a designer looking at one stage form must not
  // see two different questions under one key, wherever the core one lives.
  const problems = customDefinitionProblems(
    [section({ fields: [field({ key: "name", label: "Who runs the shed?", required: true })] })],
    [STAGE]
  );
  expect(problems.some((problem) => problem.includes("Tools: Tool name"))).toBe(true);
});

test("a custom label equal to a SINGLETON field's label of that stage is refused, case-folded", () => {
  const problems = customDefinitionProblems(
    [section({ fields: [field({ key: "households", label: "  artisan HOUSEHOLDS ", required: true })] })],
    [STAGE]
  );
  expect(problems.some((problem) => problem.includes("is already called"))).toBe(true);
});

test("a label equal to a COLLECTION field's label is ALLOWED — the narrowing is the correctness", () => {
  /*
    A collection field files its label as `"{entity.title}: {label}"` and a custom field files it
    bare, so the two can never collapse into one row of `missing`. Refusing "Tool name" on stage 4
    because a tool row has that column would be a refusal with no failure behind it — and, worse, a
    refusal the SERVER does not make, which is the one thing a pre-flight check may never do.
  */
  const problems = customDefinitionProblems(
    [section({ fields: [field({ key: "toolNote", label: "Tool name", required: true })] })],
    [STAGE]
  );
  expect(problems).toEqual([]);
});

test("with NO registry the two checks are silent, so absent evidence never becomes a refusal", () => {
  // The default parameter. A browser that has not loaded the registry has no evidence either way,
  // and blocking Save on it would be worse than the defect being fixed.
  expect(
    customDefinitionProblems([
      section({ fields: [field({ key: "artisanHouseholds", label: "Artisan households", required: true })] })
    ])
  ).toEqual([]);
});

test("a section naming a stage the registry does not carry is not double-reported", () => {
  // The unknown stage is the mistake; adding "and by the way it collides with nothing" beside it is
  // noise about the same thing. The server skips both checks when the stage does not resolve.
  const problems = customDefinitionProblems(
    [section({ stageKey: "NO_SUCH_STAGE", fields: [field({ key: "artisanHouseholds", label: "Artisan households" })] })],
    [STAGE]
  );
  expect(problems.some((problem) => problem.includes("is already a field of stage"))).toBe(false);
});

test("a sound definition is still sound once the registry is supplied", () => {
  expect(
    customDefinitionProblems(
      [section({ fields: [field({ key: "loomCount", label: "How many looms?", required: true, tier: "BASIC" })] })],
      [STAGE]
    )
  ).toEqual([]);
});
