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

function neverRead(overrides: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage(STAGE.key), ...overrides };
}

function alreadyRead(overrides: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage(STAGE.key), serverLoadedAt: 1_700_000_000_000, ...overrides };
}

const REGISTRY: DwRegistry = { version: "spec", enums: {}, stages: [STAGE] };

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

test("the reserved key and the key pattern are the server's, character for character", () => {
  expect(CUSTOM_ENTITY_KEY).toBe("_custom");

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
  for (const type of ["IMAGE", "REF", "RICH_TEXT", "GEO"]) expect(isV1CustomType(type)).toBe(false);
});

test("what comes back is not what may be sent: id, retired and supersededById never enter a PUT body", () => {
  const body = definitionBody([section({ fields: [field({})] })]);
  const sent = body.sections[0].fields[0] as Record<string, unknown>;
  expect(Object.keys(sent).sort()).toEqual(
    ["help", "key", "label", "maxLength", "maxValue", "minValue", "options", "required", "sortOrder", "tier", "type", "unit"]
  );
  expect(Object.keys(body.sections[0]).sort()).toEqual(
    ["description", "fields", "key", "sortOrder", "stageKey", "title"]
  );
  const two = definitionBody([section({ key: "b", sortOrder: 9 }), section({ key: "a", sortOrder: 9 })]);
  expect(two.sections.map((entry) => [entry.key, entry.sortOrder])).toEqual([["b", 0], ["a", 1]]);
});

test("a retired field is never dropped from a read, and a retired SECTION retires its fields", () => {
  const live = field({ key: "a", retired: false });
  const gone = field({ key: "b", retired: true, sortOrder: 1 });
  const held = definition([section({ retired: true, fields: [live, gone] })]);

  const fields = customFieldsForStage(held, STAGE.key);
  expect(fields.map((entry) => entry.key)).toEqual(["a", "b"]);
  expect(fields.every((entry) => entry.retired)).toBe(true);
});

test("a RETIRED SECTION is never named in a PUT body, because naming one un-retires it", () => {
  const body = definitionBody([
    section({ key: "gone", retired: true, fields: [field({ key: "a", retired: true })] }),
    section({ key: "live", fields: [field({ key: "b" })] })
  ]);
  expect(body.sections.map((entry) => entry.key)).toEqual(["live"]);
  expect(body.sections[0].sortOrder).toBe(0);
});

test("the field order in a body is the ARRAY's position, not the stored sortOrder", () => {
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
  expect(held[1].fields).toHaveLength(2);
});

test("an ordinary edit changes the BODY while the diff stays empty — which is why Save cannot gate on the diff", () => {
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?", help: "" })] })];
  const edited = [section({ fields: [field({ key: "loomCount", label: "How many looms?", help: "Working ones only." })] })];

  expect(diffIsEmpty(diffCustomDefinition(stored, edited, { [STAGE.key]: new Set(["loomCount"]) }))).toBe(true);
  expect(JSON.stringify(definitionBody(edited))).not.toBe(JSON.stringify(definitionBody(stored)));
  expect(JSON.stringify(definitionBody([section({ title: "Loom shed (2026)" })]))).not.toBe(
    JSON.stringify(definitionBody([section({})]))
  );
});

test("a retired SECTION's fields read as retired to every reader, so no form can draw one as live", () => {
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

  expect(retiredFields(block, { kept: 12 }).map((entry) => entry.key)).toEqual(["kept"]);
  expect(retiredFields(block, {}).map((entry) => entry.key)).toEqual([]);
  expect(retiredFields(block, { kept: "" }).map((entry) => entry.key)).toEqual([]);
});

test("a type outside the twelve is re-tokenised so no working control can be reached", () => {
  for (const raw of ["GEO", "IMAGE", "REF", "RICH_TEXT", "WIDGET"]) {
    const adapted = customFieldToDwField(field({ type: raw }));
    expect(adapted.type).toBe(`${UNSUPPORTED_FIELD_TYPE_PREFIX}${raw}`);
    expect(fieldTypeName(adapted.type)).toBe(raw);
  }
  for (const type of V1_CUSTOM_TYPES) {
    expect(customFieldToDwField(field({ type })).type).toBe(type as DwFieldType);
  }
});

test("null bounds are dropped rather than passed through as null", () => {
  const adapted = customFieldToDwField(field({ minValue: null, maxValue: null }));
  expect("minValue" in adapted).toBe(false);
  expect("maxValue" in adapted).toBe(false);
  expect(customFieldToDwField(field({ minValue: 0, maxValue: 400 }))).toMatchObject({ minValue: 0, maxValue: 400 });
});

test("the synthetic entity is per SECTION, carries only live fields, and is a singleton", () => {
  const entity = customSectionEntity(
    section({ fields: [field({ key: "a" }), field({ key: "b", retired: true, sortOrder: 1 })] })
  );
  expect(entity.key).toBe("_custom:loomShed");
  expect(entity.key).toBe(customSectionEntityKey(section({})));
  expect(entity.cardinality).toBe("SINGLETON");
  expect(entity.fields.map((entry) => entry.key)).toEqual(["a"]);
});

test("a browser holding no custom answers sends NO _custom entry at all", () => {
  for (const stage of [neverRead(), alreadyRead(), neverRead({ custom: {} }), alreadyRead({ custom: {} })]) {
    expect(buildStageEntries(STAGE, stage).entries.filter((entry) => entry.entityKey === CUSTOM_ENTITY_KEY)).toEqual([]);
  }
});

test("a never-read stage with one custom answer sends it as a MERGE", () => {
  const { entries, rowKeys } = buildStageEntries(STAGE, neverRead({ custom: { loomCount: 12 } }));
  const custom = entries.filter((entry) => entry.entityKey === CUSTOM_ENTITY_KEY);
  expect(custom).toEqual([{ entityKey: CUSTOM_ENTITY_KEY, data: { loomCount: 12 }, merge: true }]);
  expect(rowKeys).toHaveLength(entries.length);
  expect(rowKeys[entries.indexOf(custom[0])]).toBeNull();
});

test("a stage this browser HAS read sends an empty container, because that is a designer clearing it", () => {
  expect(
    buildStageEntries(STAGE, alreadyRead({ custom: { loomCount: null } })).entries.filter(
      (entry) => entry.entityKey === CUSTOM_ENTITY_KEY
    )
  ).toEqual([{ entityKey: CUSTOM_ENTITY_KEY, data: { loomCount: null } }]);
});

test("no _custom entry ever carries merge:false", () => {
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
  for (const value of ["", "   ", null, undefined]) {
    expect(
      buildStageEntries(STAGE, neverRead({ custom: { loomCount: value as never } })).entries.filter(
        (entry) => entry.entityKey === CUSTOM_ENTITY_KEY
      )
    ).toEqual([]);
  }
  expect(
    buildStageEntries(STAGE, neverRead({ custom: { loomCount: 0 } })).entries.filter(
      (entry) => entry.entityKey === CUSTOM_ENTITY_KEY
    )
  ).toEqual([{ entityKey: CUSTOM_ENTITY_KEY, data: { loomCount: 0 }, merge: true }]);
});

test("a required custom question is counted BETWEEN the singleton and the collections, under the BARE label", () => {
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

test("rewording an ANSWERED question is predicted as a supersede, and an unanswered one is free", () => {
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
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?", type: "TEXT" })] })];
  const next = [section({ fields: [field({ key: "loomCount", label: "How many looms?", type: "INT" })] })];

  const diff = diffCustomDefinition(stored, next, { [STAGE.key]: new Set(["loomCount"]) });
  expect(diff.retypedFields).toEqual([{ label: "How many looms?", was: "TEXT", now: "INT" }]);
  expect(diff.superseded).toEqual([]);
  expect(diff.retiredFields).toEqual([]);
  expect(diffIsEmpty(diff)).toBe(false);

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
  const stored = [section({ fields: [field({ key: "loomCount", label: "How many looms?" })] })];
  const moved = [section({ stageKey: "SKETCH_DEVELOPMENT", fields: [field({ key: "loomCount", label: "How many looms?" })] })];

  expect(diffCustomDefinition(stored, moved, { [STAGE.key]: new Set(["loomCount"]) }).movedSections).toEqual(["Loom shed"]);
  expect(diffCustomDefinition(stored, moved, { [STAGE.key]: new Set() }).movedSections).toEqual([]);
});

test("the readiness link for a custom question survives the round trip to the stage form", () => {
  const block = section({ key: "loomShed", fields: [field({ key: "loomCount", label: "How many looms?", required: true })] });
  const draft = draftWith(
    { [STAGE.key]: alreadyRead({ singletons: { clusterBackground: { artisanHouseholds: 412 } } }) },
    definition([block])
  );

  const readiness = workshopReadiness(REGISTRY, draft, "cmws01");
  const item = readiness.blocking.find((entry) => entry.label === "How many looms?");
  expect(item, "a required custom question must reach the readiness screen at all").toBeTruthy();
  expect(item?.address).toMatchObject({
    entityKey: customSectionEntityKey(block),
    fieldKey: "loomCount",
    anchorFieldKey: "loomCount",
    entityTitle: "Loom shed",
    rowKey: null
  });

  const focus = readStageFocus(new URLSearchParams((item?.href ?? "").split("?")[1]));
  expect(focus).toEqual({ entityKey: customSectionEntityKey(block), fieldKey: "loomCount", rowKey: null });
  expect(focus?.entityKey).toBe(customSectionEntity(block).key);
  expect(item?.href).toBe(stageFieldHref("cmws01", STAGE.key, customSectionEntityKey(block), "loomCount", null));
});

test("an ANSWERED custom question is not addressed as missing, so the walk cannot invent a blocker", () => {
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
  expect(CUSTOM_KEY_PATTERN.test(suggestCustomKey("2-ply count", new Set()))).toBe(true);
  expect(CUSTOM_KEY_PATTERN.test(suggestCustomKey("…", new Set()))).toBe(true);
  expect(suggestCustomKey("How many looms?", new Set(["howManyLooms"]))).not.toBe("howManyLooms");
  expect(CUSTOM_KEY_PATTERN.test(suggestCustomKey("a".repeat(80), new Set()))).toBe(true);
});

test("a section is retired rather than deleted when its ONLY answered key belongs to a RETIRED field", () => {
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

  const moved = [section({ stageKey: "SKETCH_DEVELOPMENT", fields: stored[0].fields })];
  expect(diffCustomDefinition(stored, moved, answered).movedSections).toEqual(["Loom shed"]);
});

test("the authoring screen asks `plan_definition`'s question, not the one its own boxes can answer", () => {
  const retiredHolder = section({
    key: "loomShed",
    fields: [
      field({ key: "loomCount", label: "How many looms?", retired: true }),
      field({ key: "dyeBath", label: "Which dye bath?", sortOrder: 1 })
    ]
  });
  const answered = { [STAGE.key]: new Set(["loomCount"]) };

  expect(storedSectionHoldsAnswer([retiredHolder], "loomShed", answered)).toBe(true);

  expect(storedSectionHoldsAnswer([retiredHolder], "loomShed", { SKETCH_DEVELOPMENT: new Set(["loomCount"]) })).toBe(
    false
  );
  expect(storedSectionHoldsAnswer([section({ ...retiredHolder, stageKey: "SKETCH_DEVELOPMENT" })], "loomShed", answered)).toBe(
    false
  );

  expect(storedSectionHoldsAnswer([], "loomShed", answered)).toBe(false);
  expect(storedSectionHoldsAnswer([retiredHolder], "shedSurvey", answered)).toBe(false);

  expect(storedSectionHoldsAnswer([retiredHolder], "loomShed", { [STAGE.key]: new Set() })).toBe(false);

  expect(diffCustomDefinition([retiredHolder], [], answered)).toMatchObject({
    retiredSections: ["Loom shed"],
    deletedSections: []
  });
});

test("a key this DEVICE still holds an unsent answer under is answered, even when the server has never seen it", () => {
  const fields = [field({ key: "loomCount", label: "How many looms?" })];

  expect([...answeredCustomKeys(fields, { loomCount: 12 })]).toEqual(["loomCount"]);
  expect([...answeredCustomKeys(fields, {}, { loomCount: 12 })]).toEqual(["loomCount"]);
  expect([...answeredCustomKeys(fields, undefined, { loomCount: 12 })]).toEqual(["loomCount"]);
  expect([...answeredCustomKeys(fields, {}, {})]).toEqual([]);
  expect([...answeredCustomKeys(fields, { loomCount: "" }, { loomCount: "   " })]).toEqual([]);
  expect([...answeredCustomKeys(fields, { loomCount: 12 }, {})]).toEqual(["loomCount"]);
});

test("a custom answer is searchable, under the section the form renders it in", () => {
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
    entityKey: customSectionEntityKey(block),
    entityTitle: "Dye shed",
    fieldKey: "dyeBath",
    fieldLabel: "Dye bath",
    anchorFieldKey: "dyeBath",
    recordTitle: null,
    rowKey: null
  });
  expect(hit.snippet.segments.map((segment) => segment.text).join("")).toContain("Indigo, unbleached");
  expect(hit.entityKey).toBe(customSectionEntity(block).key);
});

test("a stage whose ONLY answers are custom counts towards the coverage figure the panel prints", () => {
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
    { [STAGE.key]: alreadyRead({ custom: { dyeBath: "INDIGO", oldNote: "Kusuma dyeing, in the shared shed." } }) },
    definition([block])
  );
  const index = buildWorkshopSearchIndex(REGISTRY, draft);

  expect(searchWorkshop(index, "indigo").hits.map((entry) => entry.fieldKey)).toEqual(["dyeBath"]);
  expect(index.entries.find((entry) => entry.fieldKey === "dyeBath")?.text).toBe("Indigo vat");

  expect(searchWorkshop(index, "kusuma").hits).toEqual([]);
  expect(index.entries.map((entry) => entry.fieldKey)).toEqual(["dyeBath"]);
});

function backendSource(...parts: string[]): string {
  return readFileSync(join(__dirname, "..", "..", "backend", "app", ...parts), "utf8");
}

function pythonInt(source: string, name: string): number {
  const match = new RegExp(`^${name}\\s*=\\s*(\\d+)\\s*$`, "m").exec(source);
  expect(match, `${name} is no longer declared where this spec reads it`).toBeTruthy();
  return Number(match?.[1]);
}

test("the nine bounds are the service's own values, read off the Python rather than remembered", () => {
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
  expect(Object.keys(declared)).toHaveLength(9);
  expect(service.match(/^MAX_CUSTOM_[A-Z_]+\s*=\s*\d+\s*$/gm) ?? []).toHaveLength(9);

  expect(/\^\[a-z\]\[A-Za-z0-9\]\{0,39\}\$/.test(service)).toBe(true);
  expect(CUSTOM_KEY_PATTERN.source).toBe("^[a-z][A-Za-z0-9]{0,39}$");
});

test("an option's own two bounds are refused here, because they live only in the envelope", () => {
  const schema = backendSource("schemas", "design_workshops.py");
  const envelope = schema.slice(schema.indexOf("class CustomOptionIn"), schema.indexOf("class CustomFieldIn"));
  const valueChars = Number(/value:\s*str\s*=\s*Field\(min_length=1,\s*max_length=(\d+)\)/.exec(envelope)?.[1]);
  expect(valueChars, "CustomOptionIn.value no longer declares its bound where this spec reads it").toBe(64);
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

test("a custom key equal to a live registry field of that stage is refused before the round trip", () => {
  const problems = customDefinitionProblems(
    [section({ fields: [field({ key: "artisanHouseholds", label: "How many households?", required: true })] })],
    [STAGE]
  );
  expect(problems.some((problem) => problem.includes("is already a field of stage 4"))).toBe(true);
});

test("the key check walks EVERY entity of the stage, collections included", () => {
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
  const problems = customDefinitionProblems(
    [section({ fields: [field({ key: "toolNote", label: "Tool name", required: true })] })],
    [STAGE]
  );
  expect(problems).toEqual([]);
});

test("with NO registry the two checks are silent, so absent evidence never becomes a refusal", () => {
  expect(
    customDefinitionProblems([
      section({ fields: [field({ key: "artisanHouseholds", label: "Artisan households", required: true })] })
    ])
  ).toEqual([]);
});

test("a section naming a stage the registry does not carry is not double-reported", () => {
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
