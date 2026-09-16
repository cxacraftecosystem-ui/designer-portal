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

/**
 * THREE DROPDOWNS BECAME TWO, ON EVERY RECORD PAGE.
 *
 * A record form used to carry `WorkshopSelect` (a `Workshop`, saved to `workshopId`), a KIND box
 * from `stage_schema.ENUMS["WORKSHOP_KIND"]` that saved nothing and said so, and under it a second
 * workshop box (a `DesignWorkshop`, saved to `designWorkshopId`). It now carries one control with
 * two boxes: "Type of workshop", then "Workshop" — the workshops of that type, most recent first,
 * saved to whichever of the two columns the type's `routesToDesignWorkshop` flag names.
 *
 * What is pinned here is the handful of decisions that make that true and that a later edit can
 * silently undo. Every one is a real failure rather than a restatement of the code:
 *
 *  1. EVERY RECORD FORM HAS IT. The field repository wired its tracer form by form, missed four of
 *     nine mounts, and a researcher reported the feature simply absent. A picker on three of four
 *     forms is not a partial rollout; it is the divergence this release exists to end.
 *  2. THE DEFAULT IS FOR A NEW RECORD. "The most recent workshop the account can reach" must never
 *     be applied over a workshop an EXISTING record already names. Get this wrong and historic
 *     records are re-filed under whatever is newest — invisibly, because nothing on screen says a
 *     link has moved, and nobody finds out until somebody notices their records have moved.
 *  3. THE ROUTING COMES OFF THE ROW'S FLAG, NEVER OFF ITS KEY. An administrator may add a second
 *     design-workshop-backed programme the day the ministry announces one, and no client should
 *     have to ship to learn about it.
 *  4. A LIST THAT DOES NOT ARRIVE IS A FLOOR, NOT A BROKEN FORM — and the control says which of the
 *     two it is holding. A silently short list reads as "there are only these", which this
 *     repository names as its most repeated bug class.
 *  5. THE LESSONS OF THE THREE FOLDED-IN CONTROLS SURVIVE: the late-submission gate, the capped-list
 *     notice, the four sentences that tell an empty list from a failed read, and "Not linked to a
 *     workshop". Each closed a defect that shipped once already.
 *
 * SOURCE READS WHERE THE JUDGEMENT LIVES INSIDE A REACT COMPONENT — this repository has no React
 * renderer in its devDependencies — and real calls everywhere the rule could be made a function.
 * The defaulting rule, which is the one that costs data when it is wrong, is a function and is
 * CALLED. Nothing below matches a newline: `read()` normalises line endings, so a CRLF checkout and
 * CI's LF one compare the same text.
 */

const ROOT = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(ROOT, ...parts), "utf8").replace(/\r\n/g, "\n");

/**
 * Source with the prose taken out.
 *
 * Every assertion below that BANS an identifier has to read the code and not the comments, because
 * the comments in these files are where the reasons live and several of them name the very
 * identifier the code must not use. `dropdown-sweep-unit.spec.ts` carries the same two lines for
 * the same reason.
 */
const withoutComments = (source: string) =>
  source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");

const PICKER = ["components", "forms", "WorkshopPicker.tsx"];

/**
 * Every record form. Spelled out rather than globbed, because the thing this list protects against
 * is a FIFTH form arriving with the old controls — and a glob over "files that mention the picker"
 * would grow to include it only after somebody had already used the picker there.
 */
const RECORD_FORMS = [
  "components/forms/ArtisanForm.tsx",
  "components/forms/ProcessForm.tsx",
  "components/forms/ProductForm.tsx",
  "components/forms/ToolForm.tsx"
];

/** The seeded list, as `GET /workshop-types` serves it (backend/prisma/migrations/…workshop_type_options). */
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

// ══ 1. EVERY RECORD FORM IS THE ONE CONTROL ════════════════════════════════════════════════════

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
    // One control owns both columns. A form reading `designWorkshopId` off a second hook is a form
    // that can save a workshop the type box is not showing.
    expect(source, `${rel} no longer builds workshopId from the picker`).toContain(
      "workshopId: workshop.workshopId || null"
    );
    expect(source, `${rel} no longer builds designWorkshopId from the picker`).toContain(
      "designWorkshopId: workshop.designWorkshopId || null"
    );
  }
});

test("the chosen type never reaches a payload", () => {
  // R3: the record stores the WORKSHOP, never the type — the workshop already knows its own type,
  // and a second copy beside the record can disagree with `DesignWorkshop.workshopKind`, which
  // stage 1 answers and which is the only source of that fact. Asked of the FORMS, because a form is
  // where a payload is built; the picker names a `typeKey` in its own state type and must.
  for (const rel of RECORD_FORMS) {
    const source = withoutComments(read(rel));
    for (const leak of ["typeKey", "workshopType", "workshopKind"]) {
      expect(source, `${rel} mentions ${leak}. The type is not stored on the record.`).not.toContain(leak);
    }
  }
});

// ══ 2. THE DEFAULT IS FOR A NEW RECORD ═════════════════════════════════════════════════════════

test("a new record opens on Design & Prototype for an account that runs design workshops", () => {
  // R4, and the predicate that decides it stays in `lib/permissions.ts` — the picker is passed
  // `canRunDesignWorkshops(user)` rather than reading a role, so this client has one ladder.
  const key = openingTypeKey({ types: SEEDED, storedRouting: null, preferDesignWorkshops: true });
  expect(routingOf(SEEDED, key), "a designer's new record must open on the design-workshop list").toBe(true);
  expect(key).toBe(DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY);
});

test("a new record opens on the administrator's first type for everybody else", () => {
  const key = openingTypeKey({ types: SEEDED, storedRouting: null, preferDesignWorkshops: false });
  // The administrator's order, not this file's opinion — `sortWorkshopTypes` breaks a tie on `key`
  // because `sortOrder` is deliberately not unique.
  expect(key).toBe(SEEDED[0].key);
});

test("AN EXISTING RECORD KEEPS THE WORKSHOP IT NAMES, even for a designer", () => {
  /*
    THE DEFECT THIS IS THE WHOLE TEST FOR. A product filed last season against an ordinary workshop,
    opened by a DESIGNER to fix a typo: if the type box opened on Design & Prototype because that is
    a designer's default, the ordinary-workshop list would be off screen, `workshopId` would save as
    null, and the record would be re-filed — with nothing on screen saying so. The default is for a
    NEW record; `storedRouting` is what says this is not one.
  */
  const key = openingTypeKey({ types: SEEDED, storedRouting: false, preferDesignWorkshops: true });
  expect(routingOf(SEEDED, key), "a record naming a Workshop must open on the ordinary list").toBe(false);
});

test("an existing record filed under a design workshop opens there, whoever opens it", () => {
  // The mirror image, and it matters for the accounts that are NOT designers: an admin opening one
  // of the four artisans that carry a `designWorkshopId` must not have it cleared by a default.
  const key = openingTypeKey({ types: SEEDED, storedRouting: true, preferDesignWorkshops: false });
  expect(routingOf(SEEDED, key)).toBe(true);
});

test("the type list arriving late cannot move where a filed record saves", () => {
  /*
    The floor is on screen first and the served list replaces it a moment later — every time, on
    every mount. If that swap could change the ROUTING of a record that already names a workshop, the
    picker would re-file records as a function of network timing, which is the least debuggable
    version of this bug. It cannot: both answers are chosen by the flag, from the stored column.
  */
  for (const storedRouting of [true, false]) {
    const onFloor = openingTypeKey({ types: [...WORKSHOP_TYPE_FLOOR], storedRouting, preferDesignWorkshops: true });
    const onServed = openingTypeKey({ types: SEEDED, storedRouting, preferDesignWorkshops: true });
    expect(routingOf([...WORKSHOP_TYPE_FLOOR], onFloor)).toBe(storedRouting);
    expect(routingOf(SEEDED, onServed)).toBe(storedRouting);
  }
});

test("a record still names its programme after an administrator retires the type", () => {
  /*
    An administrator may deactivate the design & prototype type — `designWorkshopTypeOf` says NULL is
    a real answer — and records filed under it do not move. Falling through to the ordinary list here
    would flip the routing and clear `designWorkshopId` on the next save, which is the silent
    re-filing this file exists to prevent. The same ruling both workshop boxes already make one level
    down: the record's own answer is always an option, however old it is.
  */
  const types = deactivate(DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY);
  const key = openingTypeKey({ types, storedRouting: true, preferDesignWorkshops: true });
  expect(key, "a record filed under a design workshop lost its programme when the type was retired").toBe(
    DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY
  );
  // ...and the box can still draw it, which is what stops it rendering blank over a filed record —
  // the state whose obvious repair is picking something else.
  expect(typeOptionsFor(types, key).map((option) => option.value)).toContain(key);
});

test("a retired type is otherwise absent from the box", () => {
  // Retiring a type is exactly "it disappears from every Type of workshop dropdown". Only the row a
  // record is actually on is recovered.
  const types = deactivate("SKILL_UPGRADATION");
  const drawn = typeOptionsFor(types, DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY).map((option) => option.value);
  expect(drawn).not.toContain("SKILL_UPGRADATION");
  expect(drawn).toHaveLength(5);
});

test("a record naming neither workshop has no stored routing, so R5 survives", () => {
  // "Not linked to a workshop" is a real, valid, editable state. Nothing is pinned, so the box may
  // open wherever the defaults say — and, because both halves are told `isEdit`, neither of them
  // fills a workshop in over it.
  expect(storedRoutingOf(null, null)).toBeNull();
  expect(storedRoutingOf(undefined, undefined)).toBeNull();
  expect(storedRoutingOf("w-1", null)).toBe(false);
  expect(storedRoutingOf(null, "dw-1")).toBe(true);
  // A record carrying both is not a state the picker can create — one control gives one answer — but
  // it is a state older data could hold, and the design column wins so the more specific filing is
  // the one that survives being opened.
  expect(storedRoutingOf("w-1", "dw-1")).toBe(true);
});

test("the edit branch is spelled `isEdit`, and it reaches both halves", () => {
  const source = read(...PICKER);
  // `useWorkshopSelection` refuses to run its most-recent probe on an edit; `DesignWorkshopSelect`
  // reads `initial === undefined` as "this is a new record, fill it in for me". Both are load-bearing
  // and both are derived from one flag here rather than from a record object at four call sites.
  expect(source).toContain("useWorkshopSelection({ initialWorkshopId, isEdit, resetKey })");
  expect(
    source,
    "designInitial no longer distinguishes a create (undefined) from an edit storing nothing (null), " +
      "which is the whole of the prefill rule"
  ).toContain("const designInitial = isEdit ? (initialDesignWorkshopId ?? null) : undefined;");
});

// ══ 3. THE ROUTING IS THE ROW'S FLAG ═══════════════════════════════════════════════════════════

test("the picker never decides routing by comparing a served row's key", () => {
  const source = withoutComments(read(...PICKER));
  /*
    `lib/workshopTypes.ts`: "a `key === DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY` test in a picker is the
    bug this sentence exists to prevent". The constant appears in this file's CODE exactly three
    times and each is licensed: the import; building the FLOOR, which has no served row to read a
    flag off; and NAMING the programme a record is filed under once an administrator has retired its
    type. None of the three is a routing decision on a served row. A fourth means somebody added one.
  */
  // The IDENTIFIER, not the token it holds. Splitting on the constant's VALUE counts the
  // occurrences of "DESIGN_PROTOTYPE_DEVELOPMENT" in the prose, which is a different question and
  // one this assertion has no opinion about.
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
  // R3. The half that is not on screen contributes nothing: its id stays in its own state so
  // switching back restores it, and the form writes `|| null` over both columns.
  expect(source).toContain('workshopId: routesToDesignWorkshop ? "" : field.workshopId,');
  expect(source).toContain('designWorkshopId: routesToDesignWorkshop ? design.workshopId : "",');
});

test("the type list is not the stage registry, and does not reach for it", () => {
  const source = read(...PICKER);
  /*
    `stage_schema.ENUMS["WORKSHOP_KIND"]` answers stage 1 of a design workshop's own questionnaire and
    its `registry_version()` gates a handset's re-sync of the whole registry. The type list is a table
    an administrator edits at runtime. Same six keys today, two different questions — and deriving one
    from the other in this client is how they come to be one question again.
  */
  for (const wrong of ["fetchStageRegistry", "peekStageRegistry", "workshopKindOptions", "workshopKind"]) {
    expect(
      withoutComments(source),
      `WorkshopPicker reaches for ${wrong}. The type box is not the kind box.`
    ).not.toContain(wrong);
  }
  // The floor is the ONE thing borrowed from that vocabulary, and only because the seed was.
  expect(source).toContain("WORKSHOP_KIND_FLOOR.map(");
});

// ══ 4. THE FLOOR, AND SAYING WHICH LIST IS ON SCREEN ═══════════════════════════════════════════

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
  // DROPDOWN_DESIGN R3 — the same rule the retired kind box obeyed. A silently short list reads as
  // "there are only these", and an administrator may have added a seventh programme this browser has
  // never seen.
  expect(source).toContain("view.typesServed");
  expect(source).toContain("These are this app's built-in types of workshop");
  // R3's other sentence: the box looks exactly like the ones that are saved, and is not.
  expect(source.match(/It is not saved on this record|are not saved on this record/g)?.length ?? 0).toBeGreaterThanOrEqual(2);
});

test("a failed type read is not cached, and a successful one does not answer forever", () => {
  const source = read(...PICKER);
  // The house rule, stated in `loadAccessibleWorkshops` and `readDesignWorkshopDefault` and broken
  // once already: a cached refusal answers every form that mounts inside its window.
  expect(source).toContain("typesRequest = null;");
  expect(source).toContain("WORKSHOP_TYPES_TTL_MS");
  // Never the admin screen's parameter: a retired type absent from the dropdown is the entire
  // meaning of retiring one.
  expect(withoutComments(source), "the picker asks for inactive types").not.toContain("includeInactive");
});

// ══ 5. THE LESSONS OF THE THREE FOLDED-IN CONTROLS ═════════════════════════════════════════════

test("the late-submission gate is still called by every form, and still guards the save", () => {
  for (const rel of RECORD_FORMS) {
    expect(read(rel), `${rel} saves without awaiting the late-submission confirmation`).toContain(
      "workshop.confirmSubmission()"
    );
  }
  const source = read(...PICKER);
  /*
    IT IS A NO-OP ON THE DESIGN BRANCH, AND THAT IS NOT A WEAKENING. The window and the assignment
    roster are properties of a `Workshop` (`GET /workshops/{id}/submission-check`); a `DesignWorkshop`
    has neither, and nothing is being filed against the ordinary workshop the other half may still be
    holding. Asking anyway would raise <LateSubmissionDialog> over a workshop this save will not name.
  */
  expect(source).toContain("routesToDesignWorkshop ? Promise.resolve(true) : fieldConfirmSubmission()");
});

test("both halves still carry the capped-list notice, the state sentences and the off-page recovery", () => {
  // Every one of these closed a defect that shipped. Folding two pickers into one control must not
  // quietly drop the parts of them that were the whole point.
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
  /*
    "Not linked to a workshop" survives, and it is the SAME row on both branches. The design half's
    own default is "Not filed under a design workshop", which is right when it is one of two boxes on
    a form and wrong when it is the only one: a "no" row whose wording changes when the type box
    changes is the six-spellings-of-one-question problem `lib/workshopOptions` was written to end.
  */
  expect(source).toContain("noneLabel={NO_FIELD_WORKSHOP}");
  expect(
    read("components", "forms", "DesignWorkshopSelect.tsx"),
    "the design half no longer accepts the caller's wording for the none row"
  ).toContain("noneLabel = NO_DESIGN_WORKSHOP");
});

test("both boxes are labelled what the ruling calls them", () => {
  const source = read(...PICKER);
  // R2, literally: "Type of workshop" and "Workshop". The second label is the same on both branches
  // because the reader is answering one question; a label that changed with the type would be the
  // two-pickers-for-one-question problem wearing one control.
  expect(source).toContain('<Field label="Type of workshop">');
  // TWICE, once per branch, and that is the assertion: whichever half is drawn asks the same
  // question in the same words. One of them reading "Design & prototype workshop" is this control
  // telling the reader it has changed its mind about what it is for halfway through one question.
  expect(source.match(/label="Workshop"/g)?.length ?? 0).toBe(2);
  expect(source).toContain('<WorkshopSelect state={view.field} label="Workshop"');
});

// ══ 6. THE RECORD IN THE FORM CAN CHANGE ═══════════════════════════════════════════════════════

test("a form reused for several records re-seeds all three boxes", () => {
  const picker = read(...PICKER);
  const design = read("components", "forms", "DesignWorkshopSelect.tsx");
  /*
    THE ASYMMETRY THIS CLOSED. `useWorkshopSelection` has taken a `resetKey` since it was written and
    `useDesignWorkshopSelection` never did, so when one mounted form was handed a different record the
    ordinary-workshop box re-seeded and the design one kept the PREVIOUS record's workshop — in state,
    invisible, and on the next payload. The type box above them would have had the same hole.
  */
  expect(picker).toContain("useDesignWorkshopSelection(initialDesignWorkshopId ?? null, resetKey)");
  expect(design, "the design half ignores a record switch again").toContain("}, [resetKey, initial]);");
  expect(picker, "a person's type choice outlives the record they made it on").toContain("setChosenTypeKey(null);");
});
