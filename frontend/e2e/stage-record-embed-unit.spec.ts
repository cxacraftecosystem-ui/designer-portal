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

/**
 * THE RECORD PAGE, EMBEDDED IN THE STAGE THAT MIRRORS IT — the rules of it, and the pin that stops
 * the next mirror point being forgotten.
 *
 * WHAT THE FEATURE IS. Four stage entities ask for the same facts a repository record page already
 * collects, so those four mount the record page itself — `ArtisanForm`, `ToolForm`, `ProductForm`,
 * `ProcessForm` — with the stage's own questions in the `footerFields` slot at the bottom of the
 * same list of fields, and the boxes the linked record fills in collapsed underneath. See
 * `components/designworkshop/StageRecordEmbed.tsx` for the whole argument.
 *
 * WHY THIS FILE IS THE DEFENCE AND NOT A CODE REVIEW. The split between "filled in from the record"
 * and "the workshop's own question" is DERIVED from `DW_REFERENCE_HYDRATION`, so widening a mapping
 * moves a field between the two groups with no edit anywhere near this feature. That is the right
 * design and it has one failure mode: a mapping added for a FIFTH entity gets no embed at all, and
 * nothing anywhere says so — the stage simply goes on rendering a generated grid while the record
 * page it mirrors sits one route away. `the registry grows a mapping the table does not mention` is
 * therefore the assertion this file exists for, and it is the first test below.
 *
 * TWO KINDS OF ASSERTION, and the difference is stated rather than blurred:
 *
 *  * THE RULES, EXECUTED — the split, the pin, the refusal count. These decide what a designer sees
 *    and they are decidable without a browser, so they are run.
 *  * THE MOUNTS, READ. There is no React renderer in this repository's devDependencies (see the
 *    header of `inline-record-host-unit.spec.ts`, which lifts functions out of components for
 *    exactly this reason), so "the embed mounts the shared host rather than its own copy of the
 *    four-way switch" can only be asserted as a substring. Weaker than a render, and still the
 *    assertion that matters: the failure mode being guarded is a SECOND copy of the mount, and a
 *    second copy is visible in the source.
 *
 * THE REGISTRY IS THE REAL ONE. The fixtures below are read from the bundled schema dump the Android
 * client ships — a pure `registry_to_dict()`, so it needs no database — rather than being invented
 * here. A fixture with invented keys would hydrate nothing and pass every assertion by accident,
 * which is the way this exact class of test has failed in this repository before.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

/** The bundled registry dump — a pure `registry_to_dict()`, so it needs no database to read. */
const SCHEMA = join(ROOT, "..", "android", "app", "src", "main", "assets", "design-workshop-schema.json");

type SchemaDump = { stages: Array<{ key: string; entities: DwEntity[] }> };

const registry = JSON.parse(readFileSync(SCHEMA, "utf8")) as SchemaDump;
const entities = new Map(registry.stages.flatMap((stage) => stage.entities).map((entity) => [entity.key, entity]));

function entityOf(key: string): DwEntity {
  const entity = entities.get(key);
  if (!entity) throw new Error(`the bundled registry has no entity "${key}"`);
  return entity;
}

/* ────────────────────────────────────────────────────────────────────────────
 * THE PIN
 * ──────────────────────────────────────────────────────────────────────────── */

test("every hydration mapping is either embedded or refused in writing", () => {
  const shipped = new Set(MIRROR_POINTS.map((point) => `${point.entityKey}.${point.refFieldKey}`));
  const refused = new Set(NOT_EMBEDDED.map((entry) => entry.point));

  const unmentioned = referenceHydrationPoints().filter((key) => !shipped.has(key) && !refused.has(key));

  /*
    THE WHOLE POINT OF THIS FILE. A mapping added to `DW_REFERENCE_HYDRATION` for an entity nobody
    thought about is an entity whose designers go on retyping a record that already exists — which is
    the defect this feature was built to end, reappearing silently one entity at a time. Deciding NOT
    to embed one is a perfectly good answer; deciding it by omission is not.
  */
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
  // A row with an empty reason is a row that has not been argued, which is the state this table
  // exists to make impossible.
  for (const point of MIRROR_POINTS) expect(point.why.length).toBeGreaterThan(40);
  for (const entry of NOT_EMBEDDED) expect(entry.why.length).toBeGreaterThan(40);
});

test("the refusals are the ones that were argued, and the list has not silently grown", () => {
  /*
    PINNED BY NAME, so that "we decided not to" cannot become "we forgot to" by an edit to this
    table. Two of these four came out of the pin above on its first run rather than out of the brief:
    `existingProduct.artisanRef` and `prototype.productRef` are ONE-PAIR mappings, which are easy to
    read past when you are looking for the wide ones, and both are attributions rather than mirrors.
    Adding a fifth is a decision to be argued in `NOT_EMBEDDED`'s own text and then written here.
  */
  expect(NOT_EMBEDDED.map((entry) => entry.point).sort()).toEqual([
    "existingProduct.artisanRef",
    "processStep.processRef",
    "prototype.productRef",
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

/* ────────────────────────────────────────────────────────────────────────────
 * THE FOUR, AGAINST THE REAL REGISTRY
 * ──────────────────────────────────────────────────────────────────────────── */

test("each mirror point resolves to a REF field the inline host can actually mount", () => {
  for (const point of MIRROR_POINTS) {
    const entity = entityOf(point.entityKey);
    expect(mirrorPointFor(entity), point.entityKey).toEqual(point);
    const refField = mirrorRefField(entity, point);
    expect(refField, `${point.entityKey}.${point.refFieldKey} is not mountable`).not.toBeNull();
    expect(refField?.type).toBe("REF");
    // An entity whose mapping is empty would render an embed that hydrates nothing — the split would
    // put every field in the workshop-only group and the disclosure would be empty.
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

  // The roster has exactly one picker and it is the mirror point's own.
  expect(pickers.map((field) => field.key)).toEqual(["artisanRef"]);
  // The three the roster asks and the artisan record cannot answer: which number in the list this
  // person is at THIS workshop, whether they are the master craftsperson of it, and how many days
  // they attended. If this ever shrinks, a question is being answered from the wrong record.
  expect(workshopOnly.map((field) => field.key)).toEqual(["serialNo", "isMasterCraftsperson", "attendedDays"]);
});

test("NO REF field is ever left inside the embedded record form", () => {
  /*
    THE HAZARD, AND IT IS NOT THEORETICAL. A REF picker can open `InlineRecordDialog`. `FieldDialog`
    portals to `document.body`, so there is no nested `<form>` ELEMENT — but React propagates events
    through the REACT tree rather than the DOM tree, and the footer fields are React children of the
    embedded record form. A dialog opened from a picker down there would therefore fire the
    SURROUNDING form's `onSubmit` when the inner form is submitted (saving a product nobody asked to
    save), advance focus twice on every Enter through its `onKeyDown`, and mark it dirty through its
    `onInput` from typing in a different record entirely.

    So every REF field of a mirror-point entity is drawn ABOVE the form. On today's registry that is
    one field beyond the mirror points themselves — `existingProduct.artisanRef` — and it belongs
    there for a second, independent reason as well (see the cascade test below).
  */
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
  /*
    `existingProduct.productRef` declares `refFilterBy: "artisanRef"` and its help text says "Pick the
    artisan first to narrow this list". The registry declares `artisanRef` first, and a backend test
    pins that a REF picker is declared BEFORE the fields it fills in. Grouping is a display decision
    and must not invert that on screen: an artisan picker below the whole product record page is an
    instruction a designer meets after the thing it is about.
  */
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
    // Listed in the footer it would be a second copy of the same control on the same row.
    expect([...mirrored, ...workshopOnly].map((field) => field.key)).not.toContain(refField.key);
    expect(pickers.map((field) => field.key)).toContain(refField.key);
  }
});

test("every field of a mirror-point entity lands in exactly one group", () => {
  /*
    THE INVARIANT THE WHOLE DESIGN RESTS ON. Registry fields are relocated between three groups and
    nothing may be dropped on the way: a field that is rendered nowhere loses its `data-dw-field`
    anchor (so the workshop search sends a designer to a box that does not exist), its per-field
    refusal, its provenance stamp, and — worst — its place in `strandedRefusals`' assumption that
    every field of a rendered entity is drawn, which means a server refusal on it would be announced
    by the banner and shown by nothing.
  */
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
  // Field order is not cosmetic: it drives the report's table columns (`_table_columns` truncates at
  // six) and `registry_version()` sorts before hashing. The GROUPING is a display decision and must
  // not become a reordering — within a group, fields stay in the order the registry declares them.
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
  /*
    THE TWO-MAPPING CASE. `existingProduct` has both `productRef` (the large mapping, the one the form
    is for) and `artisanRef` (a single pair, `name -> artisanName`). The small one is the CASCADE that
    narrows the product list, and it stays an ORDINARY PICKER — drawn above the record form in the
    `pickers` group beside `productRef`, in declaration order, which is where the split puts every REF
    field of a mirror-point entity. Mounting a second record form for it would put two forms in one
    row, each able to save a different record.
  */
  const product = entityOf("existingProduct");
  const point = mirrorPointFor(product)!;
  expect(point.refFieldKey).toBe("productRef");

  const productRef = mirrorRefField(product, point)!;
  const { pickers, mirrored, workshopOnly } = splitMirroredFields(product, productRef, formFields(product));

  // An ordinary picker — drawn above the form beside the one it narrows, never a second record page.
  expect(pickers.map((field) => field.key)).toContain("artisanRef");
  expect(workshopOnly.map((field) => field.key)).not.toContain("artisanRef");
  expect(mirrored.map((field) => field.key)).not.toContain("artisanRef");
  // `artisanName` IS mirrored — it is a target of the product mapping too — so the small mapping's
  // one output is filled in from the product record, which is where the picker's cascade points.
  expect(mirrored.map((field) => field.key)).toContain("artisanName");

  // And the cascade is intact: the product list narrows on the artisan chosen above it.
  expect(productRef.refFilterBy).toBe("artisanRef");
});

test("the named-view hint's fields stay with the workshop's own questions", () => {
  // `MissingViewsHint` is rendered beside the workshop-only grid, and it is only honest there:
  // `viewFront`/`viewBack`/`viewDetail` are the stage's own slots, not anything the product record
  // fills in. Behind the mirrored disclosure the hint would be asking for something a designer would
  // reasonably expect the linked record to supply.
  const product = entityOf("existingProduct");
  const refField = mirrorRefField(product, mirrorPointFor(product)!)!;
  const { workshopOnly } = splitMirroredFields(product, refField, formFields(product));
  const keys = workshopOnly.map((field) => field.key);
  for (const view of ["viewFront", "viewBack", "viewDetail"]) expect(keys).toContain(view);
});

test("an entity with no mapping gets no embed", () => {
  // Most of the registry is not a mirror point and must go on rendering the generated grid.
  const sketch = registry.stages.flatMap((stage) => stage.entities).find((entity) => entity.key === "sketch");
  expect(sketch, "the registry has no `sketch` entity to test against").toBeTruthy();
  expect(mirrorPointFor(sketch as DwEntity)).toBeNull();
});

test("a registry that has moved on falls back to the generated grid instead of blanking the stage", () => {
  /*
    SCHEMA SKEW IS A SUPPORTED STATE, not an error — `design-workshop-schema-skew.spec.ts` is a whole
    spec about a browser holding a registry older or newer than the build. So a mirror point whose
    field the registry no longer declares, or has re-typed, resolves to null and the entity renders
    exactly as it did before this feature existed.
  */
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
  // `Craft` is a real reference model and deliberately NOT inline-creatable — see
  // `INLINE_CREATABLE` and the craft paragraph in `InlineRecordDialog`'s header.
  expect(mirrorRefField(unknownModel, point)).toBeNull();

  const deprecated: DwEntity = {
    ...entityOf("participant"),
    fields: [
      { key: "artisanRef", label: "Artisan record", type: "REF", tier: "BASIC", required: false, refModel: "Artisan", deprecated: true }
    ]
  };
  expect(mirrorRefField(deprecated, point)).toBeNull();
});

/* ────────────────────────────────────────────────────────────────────────────
 * A REFUSAL BEHIND A COLLAPSED DISCLOSURE
 * ──────────────────────────────────────────────────────────────────────────── */

test("a disclosure counts only the refusals it is actually hiding", () => {
  /*
    THE DEFECT. The advanced panel is unmounted while collapsed, so the `FieldInput` that would draw a
    server message on an ADVANCED field does not exist: the page said "The fields that need attention
    are marked below" and nothing was marked, and the refusal then recurred on every save for ever.
    `strandedRefusals` cannot cover it — it is handed no field list and no tiers, so it treats every
    key of a rendered singleton as drawn.

    The count has to be per GROUP and not per record, or a refusal on a BASIC box (which IS drawn)
    would put a red pill on a disclosure that is hiding nothing.
  */
  const advanced: DwField[] = [
    { key: "remarks", label: "Remarks", type: "TEXT", tier: "ADVANCED", required: false },
    { key: "maker", label: "Maker", type: "TEXT", tier: "ADVANCED", required: false }
  ];
  expect(refusedIn(advanced, undefined)).toBe(0);
  expect(refusedIn(advanced, {})).toBe(0);
  // A refusal on a field this group does not hold is not this group's to announce.
  expect(refusedIn(advanced, { name: "Required" })).toBe(0);
  expect(refusedIn(advanced, { remarks: "Too long" })).toBe(1);
  expect(refusedIn(advanced, { remarks: "Too long", maker: "Unknown", name: "Required" })).toBe(2);
});

/* ────────────────────────────────────────────────────────────────────────────
 * THE MOUNTS, READ
 * ──────────────────────────────────────────────────────────────────────────── */

const EMBED = read("components/designworkshop/StageRecordEmbed.tsx");
const ENTITY_FORM = read("components/designworkshop/EntityForm.tsx");
const DIALOG = read("components/designworkshop/InlineRecordDialog.tsx");
const FIELD_INPUT = read("components/designworkshop/FieldInput.tsx");
const PICKER = read("components/designworkshop/StageReferenceField.tsx");
const STAGE_PAGE = read("app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx");

test("both hosts mount the record forms through ONE shared component", () => {
  // The four forms are named in exactly one file. `forms/inlineRecordHost.ts` exists because the four
  // host callbacks were once invented four times, once per form, with three of the four missing at
  // least one; two copies of the MOUNT would repeat that failure one level up.
  for (const form of ["<ArtisanForm", "<ProductForm", "<ToolForm", "<ProcessForm"]) {
    expect(DIALOG, `${form} should be mounted in InlineRecordDialog.tsx`).toContain(form);
    expect(EMBED, `${form} must not be mounted a second time in the embed`).not.toContain(form);
  }
  expect(EMBED).toContain("<InlineRecordForm");
  expect(DIALOG).toContain("<InlineRecordForm");
});

test("the embed renders no registry field itself — it is handed grids", () => {
  /*
    Everything that makes a stage field navigable and honest lives in `FieldCell`: the
    `data-dw-field` anchor, the per-field refusal, the provenance stamp, and the stranded-refusal
    banner's assumption. A hand-rolled `FieldInput` in a record form's footer silences all four, and
    the refusal worst of all — the server would refuse a value and nothing on screen would say so.
  */
  expect(EMBED).not.toContain("<FieldInput");
  expect(EMBED).not.toContain("<FieldGrid");
  // The host draws them and passes them in as nodes. The pickers' grid carries one extra argument —
  // which record already has a form open over it, so the pencil beside it can be suppressed; see
  // "the picker above an embedded record page does not open a second editor over it" below.
  expect(ENTITY_FORM).toContain("picker={grid(groups.pickers,");
  expect(ENTITY_FORM).toContain("workshopFields={");
  expect(ENTITY_FORM).toContain("mirroredFields={");
});

test("the workshop-only fields go into the form's own footer slot", () => {
  // `footerFields` renders as the last child INSIDE the `<form>`, above its Cancel/Save row — which
  // is what makes them read as the bottom of one continuous list of fields rather than as a second
  // panel under a form that has already ended. It is deliberately not called "footer": `FieldDialog`
  // has one of those and it means the opposite thing.
  expect(EMBED).toContain("footerFields={workshopFields}");
});

test("no host has reintroduced an 'embedded, so do not prompt' flag", () => {
  /*
    The record form's unsaved-changes prompt STAYS ARMED inside the embed. The stage page has no such
    prompt because its draft is durable — but that durability is a property of the STAGE's fields,
    which the draft store writes, and not of the record form's: the name, the identity digits, the
    picked files and the captured fix live in React state and uncontrolled DOM and are read only at
    submit. An earlier pass added the flag and a reviewer argued it back out; `inlineRecordHost.ts`
    now states the conclusion in capitals. Make the embedded form's fields durable first, or keep
    asking.
  */
  for (const [name, source] of [["StageRecordEmbed", EMBED], ["InlineRecordDialog", DIALOG]] as const) {
    expect(source, `${name} must not suppress the unsaved-changes prompt`).not.toMatch(/suppressPrompt|embedded=\{true\}|skipDirty/);
  }
});

test("the mirrored boxes are HIDDEN when collapsed, never unmounted", () => {
  /*
    THE ONE WAY `MirroredFieldsDisclosure` DIFFERS FROM `AdvancedDisclosure`, and it is not a style
    choice. These boxes hold the linked record's answers on this row and they are EDITABLE —
    hydration only fills a blank, so a wrong village arriving from the record is the designer's to
    correct. Unmounted, three things go with them: the `data-dw-field` anchor a workshop search
    navigates to, the per-field message the server returned, and the provenance stamp. And
    `strandedRefusals` cannot cover the gap — it is handed no field list and no tiers, so it counts
    every key of a rendered entity as drawn, which means a refusal on an unmounted mirrored field
    would be announced by the page banner and shown by nothing at all.

    `AdvancedDisclosure` keeps unmounting, deliberately: an ADVANCED field is behind it precisely so
    forty optional boxes are not mounted on a handset, and its pill plus its auto-open are what pay
    for that.
  */
  const disclosure = EMBED.slice(EMBED.indexOf("export function MirroredFieldsDisclosure"));
  expect(disclosure).toContain('<div id={id} hidden={!open} className={open ? "mt-4 grid gap-3" : "hidden"}>');
  // The conditional-render form is the defect being guarded against.
  expect(disclosure).not.toMatch(/\{open \? \(\s*<div id=\{id\}/);
  // The panel always exists, so `aria-controls` may always name it — the opposite of the rule on
  // `AdvancedDisclosure`, where pointing at an absent id would be worse than pointing at nothing.
  expect(disclosure).toContain("aria-controls={id}");

  // And `AdvancedDisclosure` is unchanged in that respect: still conditionally rendered, still
  // compensating with a count and an auto-open.
  const advanced = ENTITY_FORM.slice(ENTITY_FORM.indexOf("function AdvancedDisclosure"));
  expect(advanced).toContain("aria-controls={open ? id : undefined}");
  expect(advanced).toContain("if (refused > 0 && had === 0) setOpen(true);");
});

test("a singleton's ADVANCED fields get their provenance stamps like every other field", () => {
  /*
    ITEM 12. `EntityForm` passed `provenance` to the singleton's primary grid and dropped it on the
    advanced one; the collection path passed it to both. So the one field in the registry that could
    never answer "did I write this, or did a colleague?" was a singleton ADVANCED field — which is
    exactly where it is least obvious, because those are the ones nobody looks at until a report
    disagrees with somebody's memory.
  */
  const singleton = ENTITY_FORM.slice(
    ENTITY_FORM.indexOf("export function EntityForm"),
    ENTITY_FORM.indexOf("* COLLECTION")
  );
  expect(singleton.length).toBeGreaterThan(500);
  /*
    THREE CONSUMERS IN THE SINGLETON BODY, and every one of them has to be handed it: the embedded
    record page's body (which forwards it to all three of its own grids), the primary grid, and the
    ADVANCED grid — the one that was missing. Counting is the only assertion available without a
    renderer, so the number is stated with what it counts rather than left as a bare 3.
  */
  expect(singleton.match(/provenance=\{provenance\}/g)?.length, "mirror body, primary grid, advanced grid").toBe(3);
  // And the advanced grid specifically: the pass is the LAST prop before the grid closes.
  const advancedGrid = singleton.slice(singleton.indexOf("<AdvancedDisclosure"));
  expect(advancedGrid, "the singleton's advanced grid must be handed provenance").toContain("provenance={provenance}");
});

test("an attached file outlives the row panel that attached it", () => {
  /*
    B4. A collection row's panel is unmounted when the row is collapsed, and `MediaField`'s pending
    list was the ONLY reference to a file that had been attached and not yet linked. Collapsing the
    row destroyed it, and about two seconds later the staged-owner release aborted the transfer and
    deleted the object already in storage; reopening the row said "Nothing attached yet".

    BOTH HALVES ARE ASSERTED, because doing only the second is worse than doing nothing: a stable
    owner id keeps the OBJECT alive with nothing in the browser left to link it, so it leaks instead
    of being cleaned up.
  */
  expect(FIELD_INPUT).toContain("const [pending, setPending] = usePendingMedia(mediaPlace);");
  expect(FIELD_INPUT).toContain("stagingOwnerId={stagingOwnerFor(mediaPlace)}");
  // Held at the page, not in the panel — so its lifetime is the stage's.
  expect(STAGE_PAGE).toContain("<StagePendingMediaProvider>");
});

test("a partly-failed batch files the surviving file against its own media id", () => {
  /*
    ITEM 6. `uploaded` is the by-index array with its NULLS FILTERED OUT, so zipping it against the
    uncompacted `chosen` misfiles every result after the first failure. `originals` has one reader —
    `IdentityCardReader`, which OCRs the ORIGINAL bytes and prints the file's name beside the digits
    — so a misfile reads one card and names another, defeating the one cross-check its header relies
    on. Never matched back by filename: two shots off one handset are both `IMG_0001.jpg`.
  */
  expect(FIELD_INPUT).toContain("uploadedByIndex.forEach((media, index) => {");
  expect(FIELD_INPUT).not.toContain("uploaded.forEach((media, index) => {");
  expect(read("lib/media.ts")).toContain("uploadedByIndex: Array<MediaFile | null>;");
});

test("every pending-media write is an updater, never a read of the render's own list", () => {
  /*
    THE HOIST'S OWN HAZARD, ONE LEVEL DOWN. `StagePendingMediaProvider` exists because a collapsed
    row destroyed the only reference to an attached file — and hoisting it into a shared map is only
    half safe: the map-level `setHeld` is an updater, which protects two DIFFERENT controls settling
    in one tick, and does nothing at all for two writes to the SAME control if the `File[]` handed to
    it was computed from a render closure. A signature committed by the capture card and the drain
    effect's filter, issued against the same committed render, would each rebuild the whole list from
    the same stale slice and the later one would win entire — the same photograph loss, arriving by
    the door the fix installed.
  */
  expect(FIELD_INPUT).toContain("attach: (file) => setPending((current) => [...current, file]),");
  expect(FIELD_INPUT).toContain("setPending((current) => current.filter((file) => !finished.includes(file)));");
  // The shape is what makes the safe call the only call: a bare list is still accepted (the capture
  // card hands one over), but the store resolves an updater against the slice it is replacing.
  expect(FIELD_INPUT).toContain("type PendingUpdate = File[] | ((current: File[]) => File[]);");
  expect(FIELD_INPUT).toContain("write: (key: string, update: PendingUpdate) => void;");
});

test("the embed supplies every non-navigating host callback the model it mounts can need", () => {
  /*
    THE THREE-OF-FOUR THREADING FAILURE, WHICH IS WHY `forms/inlineRecordHost.ts` EXISTS — and it
    happened again, to the new host. `onUseExisting` is OPTIONAL on `InlineRecordForm`, so TypeScript
    said nothing; the picker wires it and the dialog wires it and the embed did not. Without it
    `ArtisanForm` falls back to `router.push` on the existing artisan's edit route from the duplicate
    dialog, and to a bare `<Link>` in both the conflict banner and `AadhaarField` — which appears
    LIVE as the digits are typed, before any save. Every one of them abandons the half-filled
    22-stage draft, which is the single thing house rule 4 forbids. A duplicate at stage 3 is the
    ORDINARY outcome: the designer only opened the form because the picker's search did not find her.

    ASSERTED AGAINST THE EMBED AND NOT ONLY THE DIALOG. The previous version of this file read the
    callbacks off `DIALOG`, which is exactly why the gap shipped.
  */
  for (const callback of ["onCreated=", "onCancel=", "onQueued=", "onUseExisting="]) {
    expect(EMBED, `StageRecordEmbed must pass ${callback} to InlineRecordForm`).toContain(callback);
    expect(DIALOG, `InlineRecordDialog must pass ${callback} to InlineRecordForm`).toContain(callback);
  }
  // Nothing but the id crosses from the duplicate payload: `ArtisanIdentityMatch` also carries a
  // place, a craft and — on the conflict path — a MASKED identity number, and a masked Aadhaar or
  // Pehchan string must never reach a stage entry.
  expect(EMBED).toContain("(artisan: { id: string })");
  expect(EMBED).toContain("void adoptCreated(artisan.id);");
  for (const carried of ["artisan.maskedValue", "artisan.name", "artisan.place", "artisan.craft"]) {
    expect(EMBED, `${carried} must not cross from the duplicate payload onto a stage row`).not.toContain(carried);
  }
});

test("a late answer cannot write to a row that has moved on", () => {
  /*
    THE SUPERSESSION GUARD, which `StageReferenceField` has had since its picker could create and
    this host shipped without. The describe round trip is seconds long and the picker is drawn
    directly above the form, live throughout: pick a different record or press "Clear the link" in
    that window and the continuation would re-point the row back at the record the embed created,
    over the choice just made, and compute the clear-on-re-point against a `previous` two links old.
    A row naming one record while holding another's values is what `hydrateFromReference` calls the
    one outcome worse than either alternative.
  */
  expect(EMBED).toContain("const generation = hydration.current;");
  expect(EMBED).toContain("if (hydration.current !== generation) return;");
  // The bump is hung off the link changing to something this component did not claim, because the
  // PICKER writes the link too and never tells the embed it did.
  expect(EMBED).toContain("if (linkedId === claimed.current) return;");
});

test("both halves of a post-save write are read live, not out of the closure", () => {
  /*
    On the three COLLECTION mirror points `onPatch` is `(values) => patchRowMany(index, values)`, and
    `patchRowMany` rebuilds the WHOLE rows array from the `rows` captured at that render. Guarding
    the VALUES and not the WRITER is worse than guarding neither, because it reads as solved: the
    continuation would replace the collection with a snapshot taken before the save, discarding every
    edit made to every row since — including a mirrored box corrected on this component's own amber
    advice — with nothing on screen admitting it.
  */
  expect(EMBED).toContain("const live = useRef({ row, onPatch });");
  expect(EMBED).toContain("const { row: currentRow, onPatch: writeNow } = live.current;");
  expect(EMBED).toContain("const { row: current, onPatch: writeNow } = live.current;");
  // And the old closure-captured pair is gone from both adopt paths.
  expect(EMBED).not.toContain("latestRow.current");
});

test("an edit re-keys the form over the record it just wrote", () => {
  /*
    `initial` is read into React state and uncontrolled DOM at mount and never re-read, so after an
    edit the mounted form's `initialSignature`, its dirty tracking and its DOM all describe a record
    that no longer exists in that shape. On `ProcessForm` — the traditionalProcess mirror point — it
    is worse than stale: `setCommitted(true)` runs on EVERY accepted write, create and edit alike,
    after which `submit` returns early, the button is disabled and reads "Saved", and
    `hasUnsavedWork` is false. Correct a stage-5 process twice and the second correction cannot be
    saved, by a form reporting itself clean; Cancel then discards it without asking. The create path
    only ever looked safe because writing the id re-keyed the form.
  */
  const edited = EMBED.slice(EMBED.indexOf("const adoptEdited"), EMBED.indexOf("const handleSaved"));
  expect(edited.length).toBeGreaterThan(500);
  // Both exits of the edit path remount: the describable one and the "cannot describe it" one.
  expect(edited.match(/remountForm\(\);/g)?.length, "both exits of adoptEdited").toBe(2);
});

test("a GEO mirror target is compared as a point, never stringified to null", () => {
  /*
    THE SUBJECT PIN, ERASED ON EVERY SAVE. For a GEO target `inputValue` returns "" for any object,
    so the "only a box still holding what hydration wrote may move" guard never fires;
    `stringifyRefValue` returns null for any object, so the next value is null; and `null === ""` is
    false, so the box was SET TO NULL. Three mappings carry a GEO source and the value is the village
    coordinate — the only location invariant 5 lets cross. `hydrateFromReference` grew this arm; the
    edit path had not.
  */
  const edited = EMBED.slice(EMBED.indexOf("const adoptEdited"), EMBED.indexOf("const handleSaved"));
  expect(edited).toContain('if (target.type === "GEO") {');
  // An ABSENT pin writes nothing rather than null: "the record has no pin" must not delete a pin the
  // designer dropped on the village themselves.
  expect(edited).toContain("if (!nextPoint) continue;");

  /*
    AND ON THE PICKER, WHICH IS THE OTHER SURFACE THAT ADOPTS AN EDIT TO A RECORD THE ROW ALREADY
    NAMES. The embed was given this arm and `StageReferenceField` was not, so for a wave the two
    disagreed about the same row: an edit saved from the embedded page preserved the subject pin and
    the same edit saved from the picker's dialog deleted it. Asserted here rather than in a picker
    spec because it is the SAME rule, and a rule pinned on one of two hosts is how it came to be
    missing from the other.
  */
  const pickerEdit = PICKER.slice(PICKER.indexOf("const adoptEdited"), PICKER.indexOf("const adoptCreated"));
  expect(pickerEdit).toContain('if (target.type === "GEO") {');
  expect(pickerEdit).toContain("if (!nextPoint) continue;");
  // The scalar path is what erased it, so the GEO arm must come BEFORE the read that stringifies.
  expect(pickerEdit.indexOf('if (target.type === "GEO") {')).toBeLessThan(
    pickerEdit.indexOf("const current = inputValue(row[targetKey]).trim();")
  );

  /*
    And the mappings this is about are still GEO on the real registry. NOT because the mirror points
    themselves exercise the picker's copy of the arm — they cannot: `adoptEdited` is reached only
    from the edit dialog, the dialog is opened only by the pencil, and on these three the pencil is
    suppressed because `recordFormMountedOver` equals the picker's own `selectedId`. The picker
    reaches the arm on the REGISTRY-SKEW FALLBACK, where `mirrorFor` returns null because the entity
    or the field key the table names no longer matches the registry this browser holds: the stage
    then renders the ordinary grid, nothing passes `recordFormMountedOver`, and the pencil is drawn
    again over a target this loop shows is still GEO. That, and cross-surface parity — one rule
    written the same way on both hosts is what stops the next wave fixing only one of them.
  */
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
  /*
    `StageReferenceField`'s `PickerNotice` exists precisely to stop this: "a designer told in amber
    that their row is filled goes looking for what went wrong". The embed's notice was one untyped
    string rendered unconditionally in amber, so "Saved to the repository and linked to this row"
    got the same panel as the four genuine failures. Two hosts, one vocabulary.
  */
  expect(EMBED).toContain('useState<{ tone: "warn" | "done"; text: string } | null>(null)');
  expect(EMBED).toContain('tone: "done"');
  // ONE live region, present from first render, whose TEXT changes — a region that arrives with its
  // content is one assistive technology may never announce.
  expect(EMBED).toContain('<p role="status" className={liveRegionClass(busy, notice)}>');
});

test("a collapsing row asks whatever is mounted inside it first", () => {
  /*
    A row's panel is UNMOUNTED on collapse, and that was harmless while everything in it was durable.
    Two of the four mirror points are collections, so a panel can now hold a whole record form whose
    name, identity digits, attached FILES and captured fix live in React state and are read only at
    its own submit — and for a file it is worse than "lost", because `useEagerStaging` then releases
    its owner and the object already in storage is deleted about two seconds later. Opening another
    row is the same event: `openKey` is one slot, so the row that was open is unmounted either way.
  */
  expect(ENTITY_FORM).toContain("const interceptLeave = useLeaveInterceptor();");
  expect(ENTITY_FORM).toContain("if (closing !== null && interceptLeave()) return;");
  expect(ENTITY_FORM).toContain("onClick={() => toggleRow(rowKey)}");
  // And the stage's own prev/next, which were an unguarded exit for the same reason.
  expect(STAGE_PAGE).toContain("if (interceptLeave()) return;");
});

test("a clean sibling form cannot answer for a dirty one", () => {
  /*
    Stage TRADITIONAL_PROCESS_BASELINE holds BOTH a mirror-point SINGLETON (`traditionalProcess`) and
    a mirror-point COLLECTION (`tool`), so it mounts a `ProcessForm` from first paint and a `ToolForm`
    as soon as any tool row is opened: two sibling `useLeaveGuard` registrations on one page. Asking
    only the topmost meant a dirty process plus a freshly opened, clean tool row navigated with no
    prompt. The walk stops at the first blocker so at most one dialog is ever raised.
  */
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
  /*
    `LocationFields` treats "no `initial`" as the one switch that turns AUTO-CAPTURE ON, and a
    create-mode mount is exactly that: `startAutoCapture()` with `enableHighAccuracy: true`, which is
    a `watchPosition` plus the reverse-geocode chain behind it. Right on the artisan's own new-record
    page, where the researcher is standing in front of the artisan; wrong for the fourth row opened
    while looking for somebody on a 244-row roster, and it restarts every time the row is reopened. A
    mounted form also registers a leave guard and can hold attached files, so gating the mount keeps
    a row that is merely being browsed from acquiring either.

    COLLECTIONS ONLY. The one singleton mirror point mounts `ProcessForm`, which has no location card
    at all, and it is the stage's whole subject rather than one of 244.
  */
  expect(ENTITY_FORM).toContain("mountOnRequest={(anchorRowKey ?? rowKey ?? null) !== null}");
  expect(EMBED).toContain("const mounted = !mountOnRequest || Boolean(linkedId) || asked;");
  // A LINKED row is never gated: its form opens in EDIT mode, which never auto-captures, and the
  // record being right there is the whole feature.
  expect(read("components/forms/LocationFields.tsx")).toContain("if (isEditForm) return;");
});

test("a disclosure that can hold a REQUIRED box opens itself when one is unanswered", () => {
  /*
    `AdvancedDisclosure` reads `defaultOpen` exactly once, through `useState`, and that is fine for a
    group the registry guarantees is optional — `validate_registry` refuses a registry in which any
    non-BASIC tier is required. The mirrored group carries no such guarantee: measured against the
    bundled registry it holds `participant.name`, `tool.name` and `existingProduct.name`/`price`, all
    BASIC and all required. Collapsed with one of those blank, the stage's own "still needed" count
    points at a box drawn nowhere — and the panel is `display: none`, so `FieldCell`'s focus effect
    scrolls to a hidden node and the readiness jump lands silently on nothing.
  */
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
  // Watched rather than read once, so a search focus arriving after mount can still open it.
  expect(EMBED).toContain("if (defaultOpen && !had) setOpen(true);");
});

test("a search result opens the disclosures of the row it points at, and no other", () => {
  /*
    `focusIsIn` answers only "does this entity have a field by that name" — it has no rowKey concept.
    The non-mirror collection branch has always ANDed the row identity in; the mirror branch did not,
    so a result pointing at row 17's village default-opened the mirrored and advanced disclosures of
    whichever participant row the designer opened, on a 244-row roster. Both sides are null on a
    singleton, so the guard is a no-op there.
  */
  expect(ENTITY_FORM).toContain(
    "const focusHere = (focus?.rowKey ?? null) === (anchorRowKey ?? rowKey ?? null);"
  );
  expect(ENTITY_FORM).toContain("defaultOpen={focusHere && focusIsIn(focus, entity, groups.workshopAdvanced)}");
  expect(ENTITY_FORM).not.toContain("defaultOpen={focusIsIn(focus, entity, groups.mirrored)}");
});

test("the picker above an embedded record page does not open a second editor over it", () => {
  /*
    `StageReferenceSelect` draws "Edit this {noun}" whenever the row is linked and the model is
    inline-creatable — which is every mirror point — and it opened `InlineRecordDialog` on the SAME
    record the embedded form below is already mounted over. Save there and the form below still held
    the `initial` it fetched at mount, because the linked id did not change; its next Save PATCHed
    the record back to the pre-edit values. One value, two owners, older one wins.

    The embed used to RECOVER from that, remounting on a report the dialog pushed through an
    `InlineRecordSaved` context — which could throw away typing nobody had saved, and which the
    context's own docstring said should go the moment the picker could be told. It can be told now,
    so that second editor is never opened and the recovery is gone with it.

    THE TITLE IS NARROW ON PURPOSE, and "one record never has two editors" would be false. Only
    `MirroredEntityBody` passes the id, so only the pickers of an embed are covered. Stage
    TRADITIONAL_PROCESS_BASELINE's `processStep` rows point at the same Process the stage-5
    singleton has open, are refused an embed of their own, and therefore still offer the pencil.
    That case is OPEN; it is written down in `NOT_EMBEDDED` rather than asserted away here, and the
    last assertion below is what keeps it written down.
  */
  expect(PICKER).toContain("recordFormMountedOver !== selectedId ?");
  // AN ID AND NOT A FLAG. Stage 6 draws the artisan cascade picker inside the same embed as the
  // product picker, and the page below is the PRODUCT's — so the artisan's pencil has to survive.
  expect(PICKER).toContain("recordFormMountedOver?: string | null;");
  expect(ENTITY_FORM).toContain("picker={grid(groups.pickers, embeddedRecordId(refField, data) || null)}");
  // ONE function answering "which record is the form open over", used by the embed itself and by
  // the host that tells the picker — two copies of that one-liner is how the two come to disagree.
  expect(EMBED).toContain("const linkedId = embeddedRecordId(refField, row);");

  // And the recovery is really gone rather than left standing beside the fix, which would be two
  // mechanisms for one rule and a live context nothing provides. Matched on the CODE and not on the
  // name, because both files still explain in prose what used to be there and why it is not.
  expect(DIALOG).not.toContain("createContext");
  expect(DIALOG).not.toContain("useContext(");
  expect(EMBED).not.toContain("InlineRecordSaved.Provider");

  // AND THE CASE THE SUPPRESSION DOES NOT REACH STAYS ON THE RECORD. `processStep.processRef` is
  // refused an embed for its own reasons; the reason it is also a live two-editor hazard is a
  // second thing, and the two are easy to separate when the entry is next tidied.
  const step = NOT_EMBEDDED.find((entry) => entry.point === "processStep.processRef");
  expect(step, "processStep.processRef left NOT_EMBEDDED").toBeTruthy();
  expect(step!.why).toContain("recordFormMountedOver");
});
