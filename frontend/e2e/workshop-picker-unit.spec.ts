import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  WORKSHOP_TYPE_FLOOR,
  openingTypeKey,
  storedRoutingOf,
  typeOptionsFor
} from "@/components/forms/WorkshopPicker";
import { WORKSHOP_KIND_FLOOR } from "@/lib/designWorkshops";
import { DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY, type WorkshopTypeOption } from "@/lib/workshopTypes";

const ROOT = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(ROOT, ...parts), "utf8").replace(/\r\n/g, "\n");

const withoutComments = (source: string) =>
  source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");

const PICKER = ["components", "forms", "WorkshopPicker.tsx"];

const RECORD_FORMS = [
  "components/forms/ArtisanForm.tsx",
  "components/forms/ProcessForm.tsx",
  "components/forms/ProductForm.tsx",
  "components/forms/ToolForm.tsx"
];

const SEEDED: WorkshopTypeOption[] = [
  { key: "DESIGN_PROTOTYPE_DEVELOPMENT", label: "Design & Prototype Development", sortOrder: 10, routes: true },
  { key: "SKILL_UPGRADATION", label: "Skill Upgradation", sortOrder: 20, routes: false },
  { key: "DESIGN_INTERVENTION", label: "Design Intervention", sortOrder: 30, routes: false },
  { key: "CLUSTER_DEVELOPMENT", label: "Cluster Development", sortOrder: 40, routes: false },
  { key: "EXPOSURE_EXHIBITION", label: "Exposure / Exhibition", sortOrder: 50, routes: false },
  { key: "OTHER", label: "Other", sortOrder: 60, routes: false }
].map((row) => ({
  id: `seed-${row.key}`,
  key: row.key,
  label: row.label,
  sortOrder: row.sortOrder,
  isActive: true,
  routesToDesignWorkshop: row.routes,
  createdAt: null,
  updatedAt: null
}));

const deactivate = (key: string) =>
  SEEDED.map((type) => (type.key === key ? { ...type, isActive: false } : type));

const routingOf = (types: WorkshopTypeOption[], key: string) =>
  types.find((type) => type.key === key)?.routesToDesignWorkshop ?? null;

test("every record form mounts the one picker", () => {
  for (const rel of RECORD_FORMS) {
    const source = read(rel);
    expect(source, `${rel} does not mount <WorkshopPicker>.`).toMatch(/<WorkshopPicker[\s/>]/);
  }
});

test("no record form still mounts any of the three controls the picker folded in", () => {
  for (const rel of RECORD_FORMS) {
    const source = read(rel);
    for (const retired of ["WorkshopSelect", "DesignWorkshopCascade", "DesignWorkshopSelect"]) {
      expect(
        new RegExp(`<${retired}[\\s/>]`).test(source),
        `${rel} still mounts <${retired}>. Two dropdowns, never three — a form left on the old ` +
          `controls is the divergence this release exists to end, and one form differing reads to a ` +
          `researcher as the feature being broken rather than as one screen being behind.`
      ).toBe(false);
    }
  }
});

test("both ids on every save payload come from the one picker", () => {
  for (const rel of RECORD_FORMS) {
    const source = read(rel);
    expect(source, `${rel} no longer builds workshopId from the picker`).toContain(
      "workshopId: workshop.workshopId || null"
    );
    expect(source, `${rel} no longer builds designWorkshopId from the picker`).toContain(
      "designWorkshopId: workshop.designWorkshopId || null"
    );
  }
});

test("the chosen type never reaches a payload", () => {
  for (const rel of RECORD_FORMS) {
    const source = withoutComments(read(rel));
    for (const leak of ["typeKey", "workshopType", "workshopKind"]) {
      expect(source, `${rel} mentions ${leak}. The type is not stored on the record.`).not.toContain(leak);
    }
  }
});

test("a new record opens on Design & Prototype for an account that runs design workshops", () => {
  const key = openingTypeKey({ types: SEEDED, storedRouting: null, preferDesignWorkshops: true });
  expect(routingOf(SEEDED, key), "a designer's new record must open on the design-workshop list").toBe(true);
  expect(key).toBe(DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY);
});

test("a new record opens on the administrator's first type for everybody else", () => {
  const key = openingTypeKey({ types: SEEDED, storedRouting: null, preferDesignWorkshops: false });
  expect(key).toBe(SEEDED[0].key);
});

test("AN EXISTING RECORD KEEPS THE WORKSHOP IT NAMES, even for a designer", () => {
  const key = openingTypeKey({ types: SEEDED, storedRouting: false, preferDesignWorkshops: true });
  expect(routingOf(SEEDED, key), "a record naming a Workshop must open on the ordinary list").toBe(false);
});

test("an existing record filed under a design workshop opens there, whoever opens it", () => {
  const key = openingTypeKey({ types: SEEDED, storedRouting: true, preferDesignWorkshops: false });
  expect(routingOf(SEEDED, key)).toBe(true);
});

test("the type list arriving late cannot move where a filed record saves", () => {
  for (const storedRouting of [true, false]) {
    const onFloor = openingTypeKey({ types: [...WORKSHOP_TYPE_FLOOR], storedRouting, preferDesignWorkshops: true });
    const onServed = openingTypeKey({ types: SEEDED, storedRouting, preferDesignWorkshops: true });
    expect(routingOf([...WORKSHOP_TYPE_FLOOR], onFloor)).toBe(storedRouting);
    expect(routingOf(SEEDED, onServed)).toBe(storedRouting);
  }
});

test("a record still names its programme after an administrator retires the type", () => {
  const types = deactivate(DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY);
  const key = openingTypeKey({ types, storedRouting: true, preferDesignWorkshops: true });
  expect(key, "a record filed under a design workshop lost its programme when the type was retired").toBe(
    DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY
  );
  expect(typeOptionsFor(types, key).map((option) => option.value)).toContain(key);
});

test("a retired type is otherwise absent from the box", () => {
  const types = deactivate("SKILL_UPGRADATION");
  const drawn = typeOptionsFor(types, DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY).map((option) => option.value);
  expect(drawn).not.toContain("SKILL_UPGRADATION");
  expect(drawn).toHaveLength(5);
});

test("a record naming neither workshop has no stored routing, so R5 survives", () => {
  expect(storedRoutingOf(null, null)).toBeNull();
  expect(storedRoutingOf(undefined, undefined)).toBeNull();
  expect(storedRoutingOf("w-1", null)).toBe(false);
  expect(storedRoutingOf(null, "dw-1")).toBe(true);
  expect(storedRoutingOf("w-1", "dw-1")).toBe(true);
});

test("the edit branch is spelled `isEdit`, and it reaches both halves", () => {
  const source = read(...PICKER);
  expect(source).toContain("useWorkshopSelection({ initialWorkshopId, isEdit, resetKey })");
  expect(
    source,
    "designInitial no longer distinguishes a create (undefined) from an edit storing nothing (null), " +
      "which is the whole of the prefill rule"
  ).toContain("const designInitial = isEdit ? (initialDesignWorkshopId ?? null) : undefined;");
});

test("the picker never decides routing by comparing a served row's key", () => {
  const source = withoutComments(read(...PICKER));
  const occurrences = source.split("DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY").length - 1;
  expect(
    occurrences,
    "DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY is used somewhere new in WorkshopPicker. Routing is decided " +
      "by routesToDesignWorkshop, per row, because an administrator may add a second " +
      "design-workshop-backed programme without a release."
  ).toBe(3);
  expect(source, "the destination is no longer read off the row's flag").toContain(
    "types.find((type) => type.key === typeKey)?.routesToDesignWorkshop"
  );
});

test("one control, two destinations, and never both at once", () => {
  const source = read(...PICKER);
  expect(source).toContain('workshopId: routesToDesignWorkshop ? "" : field.workshopId,');
  expect(source).toContain('designWorkshopId: routesToDesignWorkshop ? design.workshopId : "",');
});

test("the type list is not the stage registry, and does not reach for it", () => {
  const source = read(...PICKER);
  for (const wrong of ["fetchStageRegistry", "peekStageRegistry", "workshopKindOptions", "workshopKind"]) {
    expect(
      withoutComments(source),
      `WorkshopPicker reaches for ${wrong}. The type box is not the kind box.`
    ).not.toContain(wrong);
  }
  expect(source).toContain("WORKSHOP_KIND_FLOOR.map(");
});

test("the built-in floor carries the seeded vocabulary, with exactly one design-workshop type", () => {
  expect(WORKSHOP_TYPE_FLOOR.map((type) => type.key)).toEqual(WORKSHOP_KIND_FLOOR.map((option) => option.value));
  const routed = WORKSHOP_TYPE_FLOOR.filter((type) => type.routesToDesignWorkshop);
  expect(
    routed.map((type) => type.key),
    "the floor must offer the design-workshop branch: a browser that has never reached " +
      "/workshop-types is the one most likely to belong to a designer in the field"
  ).toEqual([DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY]);
  expect(WORKSHOP_TYPE_FLOOR.every((type) => type.isActive)).toBe(true);
});

test("the control says whether the types came off the server", () => {
  const source = read(...PICKER);
  expect(source).toContain("view.typesServed");
  expect(source).toContain("These are the standard types of workshop");
  expect(source.match(/It is not saved on this record|are not saved on this record/g)?.length ?? 0).toBeGreaterThanOrEqual(2);
});

test("a failed type read is not cached, and a successful one does not answer forever", () => {
  const source = read(...PICKER);
  expect(source).toContain("typesRequest = null;");
  expect(source).toContain("WORKSHOP_TYPES_TTL_MS");
  expect(withoutComments(source), "the picker asks for inactive types").not.toContain("includeInactive");
});

test("the late-submission gate is still called by every form, and still guards the save", () => {
  for (const rel of RECORD_FORMS) {
    expect(read(rel), `${rel} saves without awaiting the late-submission confirmation`).toContain(
      "workshop.confirmSubmission()"
    );
  }
  const source = read(...PICKER);
  expect(source).toContain("routesToDesignWorkshop ? Promise.resolve(true) : fieldConfirmSubmission()");
});

test("both halves still carry the capped-list notice, the state sentences and the off-page recovery", () => {
  const field = read("components", "forms", "WorkshopSelect.tsx");
  const design = read("components", "forms", "DesignWorkshopSelect.tsx");
  for (const [name, source] of [
    ["WorkshopSelect", field],
    ["DesignWorkshopSelect", design]
  ] as const) {
    expect(source, `${name} no longer reports what its list left out`).toContain("workshopCutSentence");
    expect(source, `${name} no longer tells an empty list from a failed read`).toContain("workshopListNotice");
    expect(source, `${name} cannot draw the workshop already on the record`).toContain("useRecordOffPage");
    expect(source, `${name} no longer stands down when there is nothing to pick`).toContain(
      "workshopListStandsDown"
    );
  }
  expect(field, "the late-submission dialog is gone from the field picker").toContain("<LateSubmissionDialog");
});

test("R5: one spelling of 'no workshop', on whichever half is drawn", () => {
  const source = read(...PICKER);
  expect(source).toContain("noneLabel={NO_FIELD_WORKSHOP}");
  expect(
    read("components", "forms", "DesignWorkshopSelect.tsx"),
    "the design half no longer accepts the caller's wording for the none row"
  ).toContain("noneLabel = NO_DESIGN_WORKSHOP");
});

test("both boxes are labelled what the ruling calls them", () => {
  const source = read(...PICKER);
  expect(source).toContain('<Field label="Type of workshop">');
  expect(source.match(/label="Workshop"/g)?.length ?? 0).toBe(2);
  expect(source).toContain('<WorkshopSelect state={view.field} label="Workshop"');
});

test("a form reused for several records re-seeds all three boxes", () => {
  const picker = read(...PICKER);
  const design = read("components", "forms", "DesignWorkshopSelect.tsx");
  expect(picker).toContain("useDesignWorkshopSelection(initialDesignWorkshopId ?? null, resetKey)");
  expect(design, "the design half ignores a record switch again").toContain("}, [resetKey, initial]);");
  expect(picker, "a person's type choice outlives the record they made it on").toContain("setChosenTypeKey(null);");
});
