import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { refusedIn } from "@/components/designworkshop/EntityForm";
import {
  MIRROR_POINTS,
  NOT_EMBEDDED,
  mirrorPointFor,
  mirrorRefField,
  splitMirroredFields
} from "@/components/designworkshop/StageRecordEmbed";
import { formFields, referenceHydrationFor, referenceHydrationPoints, type DwEntity, type DwField } from "@/lib/designWorkshops";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const SCHEMA = join(ROOT, "..", "android", "app", "src", "main", "assets", "design-workshop-schema.json");

type SchemaDump = { stages: Array<{ key: string; entities: DwEntity[] }> };

const registry = JSON.parse(readFileSync(SCHEMA, "utf8")) as SchemaDump;
const entities = new Map(registry.stages.flatMap((stage) => stage.entities).map((entity) => [entity.key, entity]));

function entityOf(key: string): DwEntity {
  const entity = entities.get(key);
  if (!entity) throw new Error(`the bundled registry has no entity "${key}"`);
  return entity;
}

test("every hydration mapping is either embedded or refused in writing", () => {
  const shipped = new Set(MIRROR_POINTS.map((point) => `${point.entityKey}.${point.refFieldKey}`));
  const refused = new Set(NOT_EMBEDDED.map((entry) => entry.point));

  const unmentioned = referenceHydrationPoints().filter((key) => !shipped.has(key) && !refused.has(key));

  expect(
    unmentioned,
    "A reference-hydration mapping exists that StageRecordEmbed neither ships nor refuses. " +
      "Add it to MIRROR_POINTS with the reason it earns a record page, or to NOT_EMBEDDED with the " +
      "reason it does not. Do not delete this assertion."
  ).toEqual([]);
});

test("nothing is claimed twice, and every refusal carries its reason", () => {
  const shipped = MIRROR_POINTS.map((point) => `${point.entityKey}.${point.refFieldKey}`);
  const refused = NOT_EMBEDDED.map((entry) => entry.point);
  expect(new Set([...shipped, ...refused]).size).toBe(shipped.length + refused.length);
  for (const point of MIRROR_POINTS) expect(point.why.length).toBeGreaterThan(40);
  for (const entry of NOT_EMBEDDED) expect(entry.why.length).toBeGreaterThan(40);
});

test("the refusals are the ones that were argued, and the list has not silently grown", () => {
  expect(NOT_EMBEDDED.map((entry) => entry.point).sort()).toEqual([
    "artisanBaseline.interviewRef",
    "existingProduct.artisanRef",
    "finalProduct.prototypeRef",
    "processStep.processRef",
    "processStep.productRef",
    "prototype.productRef",
    "prototypeValidation.prototypeRef",
    "surveyPlan.questionnaireRef",
    "traditionalProcess.productRef",
    "workshopSetup.craftRef"
  ]);
});

test("the four shipped mirror points are the four the owner's brief named", () => {
  expect(MIRROR_POINTS.map((point) => `${point.entityKey}.${point.refFieldKey}`)).toEqual([
    "participant.artisanRef",
    "tool.toolRef",
    "traditionalProcess.processRef",
    "existingProduct.productRef"
  ]);
});

test("each mirror point resolves to a REF field the inline host can actually mount", () => {
  for (const point of MIRROR_POINTS) {
    const entity = entityOf(point.entityKey);
    expect(mirrorPointFor(entity), point.entityKey).toEqual(point);
    const refField = mirrorRefField(entity, point);
    expect(refField, `${point.entityKey}.${point.refFieldKey} is not mountable`).not.toBeNull();
    expect(refField?.type).toBe("REF");
    expect(Object.keys(referenceHydrationFor(entity, refField as DwField)).length).toBeGreaterThan(0);
  }
});

test("the split puts the mapping's targets on one side and the workshop's own questions on the other", () => {
  const participant = entityOf("participant");
  const artisanRef = mirrorRefField(participant, mirrorPointFor(participant)!)!;
  const { pickers, mirrored, workshopOnly } = splitMirroredFields(participant, artisanRef, formFields(participant));

  const targets = new Set(Object.values(referenceHydrationFor(participant, artisanRef)));
  for (const field of mirrored) expect(targets.has(field.key), `${field.key} should be mirrored`).toBe(true);
  for (const field of workshopOnly) expect(targets.has(field.key), `${field.key} should be workshop-only`).toBe(false);

  expect(pickers.map((field) => field.key)).toEqual(["artisanRef"]);
  expect(workshopOnly.map((field) => field.key)).toEqual(["serialNo", "isMasterCraftsperson", "attendedDays"]);
});

test("NO REF field is ever left inside the embedded record form", () => {
  for (const point of MIRROR_POINTS) {
    const entity = entityOf(point.entityKey);
    const refField = mirrorRefField(entity, point)!;
    const { mirrored, workshopOnly } = splitMirroredFields(entity, refField, formFields(entity));
    for (const group of [mirrored, workshopOnly]) {
      expect(
        group.filter((field) => field.type === "REF").map((field) => field.key),
        `${entity.key}: a REF picker would be rendered inside the record form`
      ).toEqual([]);
    }
  }
});

test("a cascade is answerable before the picker it narrows", () => {
  const product = entityOf("existingProduct");
  const refField = mirrorRefField(product, mirrorPointFor(product)!)!;
  expect(refField.refFilterBy).toBe("artisanRef");
  const { pickers } = splitMirroredFields(product, refField, formFields(product));
  expect(pickers.map((field) => field.key)).toEqual(["artisanRef", "productRef"]);
});

test("the picker itself is in neither of the other two groups — it is drawn above both", () => {
  for (const point of MIRROR_POINTS) {
    const entity = entityOf(point.entityKey);
    const refField = mirrorRefField(entity, point)!;
    const { pickers, mirrored, workshopOnly } = splitMirroredFields(entity, refField, formFields(entity));
    expect([...mirrored, ...workshopOnly].map((field) => field.key)).not.toContain(refField.key);
    expect(pickers.map((field) => field.key)).toContain(refField.key);
  }
});

test("every field of a mirror-point entity lands in exactly one group", () => {
  for (const point of MIRROR_POINTS) {
    const entity = entityOf(point.entityKey);
    const refField = mirrorRefField(entity, point)!;
    const drawn = formFields(entity);
    const { pickers, mirrored, workshopOnly } = splitMirroredFields(entity, refField, drawn);
    const seen = [...pickers, ...mirrored, ...workshopOnly].map((field) => field.key);
    expect(new Set(seen).size, `${entity.key}: a field is in two groups`).toBe(seen.length);
    expect(seen.sort()).toEqual(drawn.map((field) => field.key).sort());
  }
});

test("declaration order survives the regrouping", () => {
  for (const point of MIRROR_POINTS) {
    const entity = entityOf(point.entityKey);
    const refField = mirrorRefField(entity, point)!;
    const drawn = formFields(entity).map((field) => field.key);
    const { pickers, mirrored, workshopOnly } = splitMirroredFields(entity, refField, formFields(entity));
    for (const group of [pickers, mirrored, workshopOnly]) {
      const positions = group.map((field) => drawn.indexOf(field.key));
      expect(positions, `${entity.key}: a group is out of declaration order`).toEqual([...positions].sort((a, b) => a - b));
    }
  }
});

test("existingProduct keeps its SECOND mapping as an ordinary picker, not a second embedded form", () => {
  const product = entityOf("existingProduct");
  const point = mirrorPointFor(product)!;
  expect(point.refFieldKey).toBe("productRef");

  const productRef = mirrorRefField(product, point)!;
  const { pickers, mirrored, workshopOnly } = splitMirroredFields(product, productRef, formFields(product));

  expect(pickers.map((field) => field.key)).toContain("artisanRef");
  expect(workshopOnly.map((field) => field.key)).not.toContain("artisanRef");
  expect(mirrored.map((field) => field.key)).not.toContain("artisanRef");
  expect(mirrored.map((field) => field.key)).toContain("artisanName");

  expect(productRef.refFilterBy).toBe("artisanRef");
});

test("the named-view hint's fields stay with the workshop's own questions", () => {
  const product = entityOf("existingProduct");
  const refField = mirrorRefField(product, mirrorPointFor(product)!)!;
  const { workshopOnly } = splitMirroredFields(product, refField, formFields(product));
  const keys = workshopOnly.map((field) => field.key);
  for (const view of ["viewFront", "viewBack", "viewDetail"]) expect(keys).toContain(view);
});

test("an entity with no mapping gets no embed", () => {
  const sketch = registry.stages.flatMap((stage) => stage.entities).find((entity) => entity.key === "sketch");
  expect(sketch, "the registry has no `sketch` entity to test against").toBeTruthy();
  expect(mirrorPointFor(sketch as DwEntity)).toBeNull();
});

test("a registry that has moved on falls back to the generated grid instead of blanking the stage", () => {
  const point = mirrorPointFor(entityOf("participant"))!;
  const withoutTheField: DwEntity = { ...entityOf("participant"), fields: [] };
  expect(mirrorRefField(withoutTheField, point)).toBeNull();

  const retyped: DwEntity = {
    ...entityOf("participant"),
    fields: [{ key: "artisanRef", label: "Artisan record", type: "TEXT", tier: "BASIC", required: false }]
  };
  expect(mirrorRefField(retyped, point)).toBeNull();

  const unknownModel: DwEntity = {
    ...entityOf("participant"),
    fields: [{ key: "artisanRef", label: "Artisan record", type: "REF", tier: "BASIC", required: false, refModel: "Craft" }]
  };
  expect(mirrorRefField(unknownModel, point)).toBeNull();

  const deprecated: DwEntity = {
    ...entityOf("participant"),
    fields: [
      { key: "artisanRef", label: "Artisan record", type: "REF", tier: "BASIC", required: false, refModel: "Artisan", deprecated: true }
    ]
  };
  expect(mirrorRefField(deprecated, point)).toBeNull();
});

test("a disclosure counts only the refusals it is actually hiding", () => {
  const advanced: DwField[] = [
    { key: "remarks", label: "Remarks", type: "TEXT", tier: "ADVANCED", required: false },
    { key: "maker", label: "Maker", type: "TEXT", tier: "ADVANCED", required: false }
  ];
  expect(refusedIn(advanced, undefined)).toBe(0);
  expect(refusedIn(advanced, {})).toBe(0);
  expect(refusedIn(advanced, { name: "Required" })).toBe(0);
  expect(refusedIn(advanced, { remarks: "Too long" })).toBe(1);
  expect(refusedIn(advanced, { remarks: "Too long", maker: "Unknown", name: "Required" })).toBe(2);
});

const EMBED = read("components/designworkshop/StageRecordEmbed.tsx");
const ENTITY_FORM = read("components/designworkshop/EntityForm.tsx");
const DIALOG = read("components/designworkshop/InlineRecordDialog.tsx");
const FIELD_INPUT = read("components/designworkshop/FieldInput.tsx");
const PICKER = read("components/designworkshop/StageReferenceField.tsx");
const STAGE_PAGE = read("app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx");

test("both hosts mount the record forms through ONE shared component", () => {
  for (const form of ["<ArtisanForm", "<ProductForm", "<ToolForm", "<ProcessForm"]) {
    expect(DIALOG, `${form} should be mounted in InlineRecordDialog.tsx`).toContain(form);
    expect(EMBED, `${form} must not be mounted a second time in the embed`).not.toContain(form);
  }
  expect(EMBED).toContain("<InlineRecordForm");
  expect(DIALOG).toContain("<InlineRecordForm");
});

test("the embed renders no registry field itself — it is handed grids", () => {
  expect(EMBED).not.toContain("<FieldInput");
  expect(EMBED).not.toContain("<FieldGrid");
  expect(ENTITY_FORM).toContain("picker={grid(groups.pickers,");
  expect(ENTITY_FORM).toContain("workshopFields={");
  expect(ENTITY_FORM).toContain("mirroredFields={");
});

test("the workshop-only fields go into the form's own footer slot", () => {
  expect(EMBED).toContain("footerFields={workshopFields}");
});

test("no host has reintroduced an 'embedded, so do not prompt' flag", () => {
  for (const [name, source] of [["StageRecordEmbed", EMBED], ["InlineRecordDialog", DIALOG]] as const) {
    expect(source, `${name} must not suppress the unsaved-changes prompt`).not.toMatch(/suppressPrompt|embedded=\{true\}|skipDirty/);
  }
});

test("the mirrored boxes are HIDDEN when collapsed, never unmounted", () => {
  const disclosure = EMBED.slice(EMBED.indexOf("export function MirroredFieldsDisclosure"));
  expect(disclosure).toContain('<div id={id} hidden={!open} className={open ? "mt-4 grid gap-3" : "hidden"}>');
  expect(disclosure).not.toMatch(/\{open \? \(\s*<div id=\{id\}/);
  expect(disclosure).toContain("aria-controls={id}");

  const advanced = ENTITY_FORM.slice(ENTITY_FORM.indexOf("function AdvancedDisclosure"));
  expect(advanced).toContain("aria-controls={open ? id : undefined}");
  expect(advanced).toContain("if (refused > 0 && had === 0) setOpen(true);");
});

test("a singleton's ADVANCED fields get their provenance stamps like every other field", () => {
  const singleton = ENTITY_FORM.slice(
    ENTITY_FORM.indexOf("export function EntityForm"),
    ENTITY_FORM.indexOf("* COLLECTION")
  );
  expect(singleton.length).toBeGreaterThan(500);
  expect(singleton.match(/provenance=\{provenance\}/g)?.length, "mirror body, primary grid, advanced grid").toBe(3);
  const advancedGrid = singleton.slice(singleton.indexOf("<AdvancedDisclosure"));
  expect(advancedGrid, "the singleton's advanced grid must be handed provenance").toContain("provenance={provenance}");
});

test("an attached file outlives the row panel that attached it", () => {
  expect(FIELD_INPUT).toContain("const [pending, setPending] = usePendingMedia(mediaPlace);");
  expect(FIELD_INPUT).toContain("stagingOwnerId={stagingOwnerFor(mediaPlace)}");
  expect(STAGE_PAGE).toContain("<StagePendingMediaProvider>");
});

test("a partly-failed batch files the surviving file against its own media id", () => {
  expect(FIELD_INPUT).toContain("uploadedByIndex.forEach((media, index) => {");
  expect(FIELD_INPUT).not.toContain("uploaded.forEach((media, index) => {");
  expect(read("lib/media.ts")).toContain("uploadedByIndex: Array<MediaFile | null>;");
});

test("every pending-media write is an updater, never a read of the render's own list", () => {
  expect(FIELD_INPUT).toContain("attach: (file) => setPending((current) => [...current, file]),");
  expect(FIELD_INPUT).toContain("setPending((current) => current.filter((file) => !finished.includes(file)));");
  expect(FIELD_INPUT).toContain("type PendingUpdate = File[] | ((current: File[]) => File[]);");
  expect(FIELD_INPUT).toContain("write: (key: string, update: PendingUpdate) => void;");
});

test("the embed supplies every non-navigating host callback the model it mounts can need", () => {
  for (const callback of ["onCreated=", "onCancel=", "onQueued=", "onUseExisting="]) {
    expect(EMBED, `StageRecordEmbed must pass ${callback} to InlineRecordForm`).toContain(callback);
    expect(DIALOG, `InlineRecordDialog must pass ${callback} to InlineRecordForm`).toContain(callback);
  }
  expect(EMBED).toContain("(artisan: { id: string })");
  expect(EMBED).toContain("void adoptCreated(artisan.id);");
  for (const carried of ["artisan.maskedValue", "artisan.name", "artisan.place", "artisan.craft"]) {
    expect(EMBED, `${carried} must not cross from the duplicate payload onto a stage row`).not.toContain(carried);
  }
});

test("a late answer cannot write to a row that has moved on", () => {
  expect(EMBED).toContain("const generation = hydration.current;");
  expect(EMBED).toContain("if (hydration.current !== generation) return;");
  expect(EMBED).toContain("if (linkedId === claimed.current) return;");
});

test("both halves of a post-save write are read live, not out of the closure", () => {
  expect(EMBED).toContain("const live = useRef({ row, onPatch });");
  expect(EMBED).toContain("const { row: currentRow, onPatch: writeNow } = live.current;");
  expect(EMBED).toContain("const { row: current, onPatch: writeNow } = live.current;");
  expect(EMBED).not.toContain("latestRow.current");
});

test("an edit re-keys the form over the record it just wrote", () => {
  const edited = EMBED.slice(EMBED.indexOf("const adoptEdited"), EMBED.indexOf("const handleSaved"));
  expect(edited.length).toBeGreaterThan(500);
  expect(edited.match(/remountForm\(\);/g)?.length, "both exits of adoptEdited").toBe(2);
});

test("a GEO mirror target is compared as a point, never stringified to null", () => {
  const edited = EMBED.slice(EMBED.indexOf("const adoptEdited"), EMBED.indexOf("const handleSaved"));
  expect(edited).toContain('if (target.type === "GEO") {');
  expect(edited).toContain("if (!nextPoint) continue;");

  const pickerEdit = PICKER.slice(PICKER.indexOf("const adoptEdited"), PICKER.indexOf("const adoptCreated"));
  expect(pickerEdit).toContain('if (target.type === "GEO") {');
  expect(pickerEdit).toContain("if (!nextPoint) continue;");
  expect(pickerEdit.indexOf('if (target.type === "GEO") {')).toBeLessThan(
    pickerEdit.indexOf("const current = inputValue(row[targetKey]).trim();")
  );

  for (const [entityKey, targetKey] of [
    ["participant", "subjectLocation"],
    ["tool", "recordSubjectLocation"],
    ["existingProduct", "recordSubjectLocation"]
  ] as const) {
    const entity = entityOf(entityKey);
    const refField = mirrorRefField(entity, mirrorPointFor(entity)!)!;
    expect(Object.values(referenceHydrationFor(entity, refField))).toContain(targetKey);
    expect(entity.fields.find((field) => field.key === targetKey)?.type, `${entityKey}.${targetKey}`).toBe("GEO");
  }
});

test("a confirmation is not drawn in the colour a refusal is drawn in", () => {
  expect(EMBED).toContain('useState<{ tone: "warn" | "done"; text: string } | null>(null)');
  expect(EMBED).toContain('tone: "done"');
  expect(EMBED).toContain('<p role="status" className={liveRegionClass(busy, notice)}>');
});

test("a collapsing row asks whatever is mounted inside it first", () => {
  expect(ENTITY_FORM).toContain("const interceptLeave = useLeaveInterceptor();");
  expect(ENTITY_FORM).toContain("const open = () => setOpenKey((current) => (current === rowKey ? null : rowKey));");
  expect(ENTITY_FORM).toContain("if (closing !== null && interceptLeave(open)) return;");
  expect(ENTITY_FORM).toContain("onClick={() => toggleRow(rowKey)}");
  expect(STAGE_PAGE).toContain("if (interceptLeave(() => void go())) return;");
});

test("a clean sibling form cannot answer for a dirty one", () => {
  const guard = read("components/UnsavedChangesGuard.tsx");
  expect(guard).toContain("for (let index = stack.length - 1; index >= 0; index -= 1) {");
  expect(guard).not.toContain("const top = interceptors.current[interceptors.current.length - 1];");

  const baseline = registry.stages.find((stage) => stage.key === "TRADITIONAL_PROCESS_BASELINE");
  expect(baseline, "the registry has no TRADITIONAL_PROCESS_BASELINE stage").toBeTruthy();
  const mirrored = (baseline as { entities: DwEntity[] }).entities
    .filter((entity) => mirrorPointFor(entity))
    .map((entity) => entity.key);
  expect(mirrored, "one stage, two record forms — this is why the stack is walked").toEqual([
    "traditionalProcess",
    "tool"
  ]);
});

test("an unlinked collection row does not mount a form, and so does not start a GPS watch", () => {
  expect(ENTITY_FORM).toContain("mountOnRequest={(anchorRowKey ?? rowKey ?? null) !== null}");
  expect(EMBED).toContain("const mounted = !mountOnRequest || Boolean(linkedId) || asked;");
  expect(read("components/forms/LocationFields.tsx")).toContain("if (isEditForm) return;");
});

test("a disclosure that can hold a REQUIRED box opens itself when one is unanswered", () => {
  const required: string[] = [];
  for (const point of MIRROR_POINTS) {
    const entity = entityOf(point.entityKey);
    const refField = mirrorRefField(entity, point)!;
    const { mirrored } = splitMirroredFields(entity, refField, formFields(entity));
    for (const field of mirrored) if (field.required) required.push(`${entity.key}.${field.key}`);
  }
  expect(required.sort()).toEqual([
    "existingProduct.name",
    "existingProduct.price",
    "participant.name",
    "tool.name"
  ]);

  expect(ENTITY_FORM).toContain(
    "const mirroredNeeded = groups.mirrored.some((field) => field.required && !isFilled(data[field.key]));"
  );
  expect(ENTITY_FORM).toContain(
    "defaultOpen={mirroredNeeded || (focusHere && focusIsIn(focus, entity, groups.mirrored))}"
  );
  expect(EMBED).toContain("if (defaultOpen && !had) setOpen(true);");
});

test("a search result opens the disclosures of the row it points at, and no other", () => {
  expect(ENTITY_FORM).toContain(
    "const focusHere = (focus?.rowKey ?? null) === (anchorRowKey ?? rowKey ?? null);"
  );
  expect(ENTITY_FORM).toContain("defaultOpen={focusHere && focusIsIn(focus, entity, groups.workshopAdvanced)}");
  expect(ENTITY_FORM).not.toContain("defaultOpen={focusIsIn(focus, entity, groups.mirrored)}");
});

test("the picker above an embedded record page does not open a second editor over it", () => {
  expect(PICKER).toContain("recordFormMountedOver !== selectedId ?");
  expect(PICKER).toContain("recordFormMountedOver?: string | null;");
  expect(ENTITY_FORM).toContain("picker={grid(groups.pickers, embeddedRecordId(refField, data) || null)}");
  expect(EMBED).toContain("const linkedId = embeddedRecordId(refField, row);");

  expect(DIALOG).not.toContain("createContext");
  expect(DIALOG).not.toContain("useContext(");
  expect(EMBED).not.toContain("InlineRecordSaved.Provider");

  const step = NOT_EMBEDDED.find((entry) => entry.point === "processStep.processRef");
  expect(step, "processStep.processRef left NOT_EMBEDDED").toBeTruthy();
  expect(step!.why).toContain("recordFormMountedOver");
});
