import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { inlineSeed, refusedByUnsavedWork, scopeNoticeLines } from "@/components/designworkshop/StageReferenceField";
import { seedHasArtisan } from "@/components/forms/inlineRecordHost";
import { MEASUREMENT_GRID_PURPOSE } from "@/components/media/GridMeasurement";
import type { DwEntity, DwField, DwReferencePayload } from "@/lib/designWorkshops";

/**
 * FILLING IN AN ARTISAN, PRODUCT, TOOL OR PROCESS THROUGH THE WORKSHOP FLOW — the four ways it
 * failed in the field, pinned.
 *
 * `InlineRecordDialog` mounts the four real record forms over a half-filled stage so a designer who
 * finds the record missing does not have to abandon a 22-stage form to make it. Every one of the
 * defects below is that feature failing on a form that was written for a full-page route and never
 * re-audited for its second host:
 *
 *  1. **Cancel navigated.** All three of `ArtisanForm` / `ProductForm` / `ToolForm` ended their
 *     Cancel — and their unsaved-changes "Discard" — in `router.back()`. The dialog is not a route,
 *     so back popped the real history entry and the stage disappeared. Cancel is the most natural
 *     way to back out of a modal and it was the one control that lost the designer's place.
 *  2. **The create was filed against the wrong parent.** The dialog passed the forms no context at
 *     all, though the picker rendering it held the row's artisan and the workshop. The forms fell
 *     back to the carry bag — the last artisan documented anywhere — so a product created from
 *     Kamla's row was filed under somebody else, excluded by the server's own cascade from the list
 *     it was made in, undescribable, and left two required boxes blank until the stage 422'd.
 *  3. **An offline create said nothing.** The queued branch returned silently and relied on
 *     `OutboxBanner`, which is mounted outside the dialog's portal on a body whose scroll the
 *     dialog has locked. The button flipped back from "Saving…" and nothing else changed — read as
 *     a failed save, answered by pressing it again, three copies in the outbox.
 *  4. **The duplicate prompt navigated.** "Open the existing record" did `router.push` to that
 *     artisan's edit page, so acting on the very thing the prompt exists to surface cost the stage.
 *
 * A THIRD HOST HAS SINCE APPEARED — `StageRecordEmbed` draws these same four forms INSIDE a stage,
 * where they are neither a route nor a modal — and it turned two of the assumptions above into
 * defects of their own, both pinned below:
 *
 *  1b. **"Discard" discarded and did not leave.** The form's own Cancel and the page header's back
 *      arrow raise the same prompt and took the same answer, which is correct in a dialog (it
 *      closes) and wrong in an embed (`onCancel` REMOUNTS the form in place). The designer pressed
 *      Back, said Discard, lost everything they had typed and stayed exactly where they were.
 *      THE FORMS ROUTE THE BACK ARROW THROUGH `onDiscardAndLeave` AND THE EMBED NOW SUPPLIES ONE,
 *      so the two exits no longer share an answer. THE REFUSED ACT NOW SURVIVES THE REFUSAL TOO:
 *      `UnsavedChangesGuard` is handed what each control was about to do, holds it against the form
 *      that blocked, and offers `completeLeave` / `abandonLeave` to that form alone. What is left is
 *      the answer itself — the four forms' "Discard" does not yet call `completeLeave()`, so the
 *      embed still clears the form and says the page did not move, and the picker's own refusals say
 *      the same thing rather than promising an exit no form performs. Both halves are below: the
 *      carrying is a real test, the answering is the one `test.fixme` after it, and it is that fixme
 *      that requires both sentences to change when the calls land.
 *  1c. **A form titled a screen its host had already titled.** `ProcessForm` was the only one of the
 *      four with its own `<h2>`, written when /processes was its only host. Every host titles the
 *      surface itself — the page with the SAME two strings — so stage 5 painted two sibling `h2`s
 *      for one thing and /processes painted an `h1` and an `h2` reading alike. The heading is gone.
 *
 * TWO MORE share this file because they are the same class of thing — a repository record failing to
 * reach a stage:
 *
 *  5. **The measurement-grid marker**, which stops a sheet of graph paper being printed in a
 *     ministry report as the photograph of a tool.
 *  6. **An empty picker read as an empty repository.** `ScopeNotice` spoke only when the design
 *     workshop was UNLINKED. A LINKED one with nothing attached to its workshop record showed the
 *     generic "No records to choose from yet." — a claim about the repository where the truth was a
 *     claim about the filter — and the designer typed the records in by hand.
 *
 * ── WHICH HALF OF THIS IS EXECUTED, AND WHAT THE OTHER HALF CAN AND CANNOT CATCH ──────────────
 * Stated plainly because it is a weakness rather than a preference: there is no React renderer in
 * this repository's devDependencies (Playwright is the whole of it), so a component cannot be
 * mounted at all and a rule living inside a component body can only be READ. `discarded-work-unit`
 * and `derived-fields-unit` read source for the same reason.
 *
 * WHERE A RULE COULD BE LIFTED OUT OF A COMPONENT IT WAS, and those are the tests worth trusting:
 * {@link inlineSeed} and {@link scopeNoticeLines} are real functions called with real arguments
 * here, over the inputs that actually occur — a roster-entry id where an `Artisan` id is expected,
 * a linked workshop with nothing under it. Both were lifted because their failure is SILENT: a
 * product filed under a roster entry looks like a product, and a picker that explains itself wrongly
 * looks like a picker.
 *
 * A SOURCE ASSERTION PINS THE SPELLING OF A LINE, NOT ITS EFFECT, and that limit is real rather than
 * theoretical. `expect(source).toContain("leave();")` proves the discard branch names the exit
 * function; it cannot prove `leave` is reached, and it would pass with the literal sitting in a
 * branch nothing runs. So every one of them is written to fail against the OLD tree — that much
 * they do prove — and where an assertion could be aimed at a PLACE rather than at a count, it is:
 * see the grid-marker tests, which slice the two upload calls out of the form and require the marker
 * inside them and absent everywhere else, because "both literals are present somewhere" was true of
 * the version that wrote the marker onto the wrong batch.
 *
 * None of them proves anything PAINTS. `inline-record-create.spec.ts` and
 * `inline-create-hydration.spec.ts` are where a browser is driven, and they need the stack.
 */

const ROOT = join(__dirname, "..");
/**
 * One source file, with its line endings normalised.
 *
 * The working trees on this project are checked out CRLF on Windows and LF elsewhere, and an
 * assertion about a multi-line block would otherwise pass on one developer's machine and fail on
 * the next for a reason that has nothing to do with the code.
 */
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const ARTISAN_FORM = "components/forms/ArtisanForm.tsx";
const PRODUCT_FORM = "components/forms/ProductForm.tsx";
const TOOL_FORM = "components/forms/ToolForm.tsx";
const PROCESS_FORM = "components/forms/ProcessForm.tsx";
const DIALOG = "components/designworkshop/InlineRecordDialog.tsx";
const PICKER = "components/designworkshop/StageReferenceField.tsx";

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The seed — the one rule that is executed rather than read
 * ──────────────────────────────────────────────────────────────────────────── */

function field(overrides: Partial<DwField> & { key: string }): DwField {
  return { label: overrides.key, type: "REF", tier: "BASIC", required: false, ...overrides } as DwField;
}

/** Stage 6 — the cascade whose filter really does hold an `Artisan` id. */
const EXISTING_PRODUCT: DwEntity = {
  key: "existingProduct",
  name: "DwExistingProduct",
  cardinality: "COLLECTION",
  title: "Existing products",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field({ key: "artisanRef", refModel: "Artisan", refScope: "WORKSHOP" }),
    field({ key: "productRef", refModel: "ProductDocumentation", refScope: "WORKSHOP", refFilterBy: "artisanRef" }),
    field({ key: "artisanName", type: "TEXT" })
  ]
};

/** Stage 13 — the same-named cascade whose filter holds a `DwParticipant` ROSTER ENTRY id. */
const PROTOTYPE: DwEntity = {
  key: "prototype",
  name: "DwPrototype",
  cardinality: "COLLECTION",
  title: "Prototypes",
  description: "",
  parent: "",
  labelField: "name",
  fields: [
    field({ key: "artisanRef", refModel: "DwParticipant", refScope: "ALL" }),
    field({ key: "productRef", refModel: "ProductDocumentation", refScope: "WORKSHOP", refFilterBy: "artisanRef" }),
    field({ key: "productName", type: "TEXT" })
  ]
};

const productRefOf = (entity: DwEntity) => entity.fields.find((f) => f.key === "productRef") as DwField;

test("a product created from stage 6's cascade is seeded with the artisan the row names", () => {
  const seed = inlineSeed({
    entity: EXISTING_PRODUCT,
    field: productRefOf(EXISTING_PRODUCT),
    row: { artisanRef: "artisan_kamla", artisanName: "Kamla Devi" },
    filterValue: "artisan_kamla",
    linkedWorkshopId: null
  });
  expect(seed.artisanId).toBe("artisan_kamla");
  // The NAME as well as the id: `artisanName` is a REQUIRED free-text box on the product form, and a
  // form that opens with a linked artisan and a blank "Artisan name" is one the designer has to
  // retype an answer into that is already on screen beside it.
  expect(seed.artisanName).toBe("Kamla Devi");
});

test("a product created from stage 13's roster cascade is NOT seeded with the participant id", () => {
  /*
    THE NEGATIVE CONTROL FOR THE WHOLE SEED. `prototype.artisanRef` holds a `DwParticipant` entry
    id, not an `Artisan` id — the maker was chosen from stage 3's list of who was in the room — and
    the SERVER resolves it with `_artisan_id_behind`, a rule it deliberately spares the clients. A
    browser cannot follow it, so seeding the value it happens to be holding would write a
    roster-entry id into `ProductDocumentation.artisanId`: a foreign key pointing at the wrong
    table, on a record nothing on any screen would flag. Filing under nobody is strictly better.
  */
  const seed = inlineSeed({
    entity: PROTOTYPE,
    field: productRefOf(PROTOTYPE),
    row: { artisanRef: "dwentry_participant_7" },
    filterValue: "dwentry_participant_7",
    linkedWorkshopId: null
  });
  expect(seed.artisanId).toBeUndefined();
  expect(seed.artisanName).toBeUndefined();
});

test("an uncascaded picker seeds no artisan at all", () => {
  // The roster picker itself (`participant.artisanRef`) and every other plain REF. There is no
  // parent on the row to carry, and inventing one would be the claim Android's inline record host
  // refuses to make.
  const seed = inlineSeed({ field: field({ key: "artisanRef", refModel: "Artisan" }), linkedWorkshopId: "ws_1" });
  expect(seed.artisanId).toBeUndefined();
  expect(seed.workshopId).toBe("ws_1");
});

test("nothing is guessed when the design workshop has no linked workshop", () => {
  /*
    The unlinked case is real and the references endpoint already reports it (`scopedToWorkshop:
    false`, which `ScopeNotice` prints out loud). Filling a workshop in there would file the record
    against a sitting it was not documented at, which is worse than the widened list the designer
    has already been warned about.
  */
  const seed = inlineSeed({
    entity: EXISTING_PRODUCT,
    field: productRefOf(EXISTING_PRODUCT),
    row: { artisanRef: "artisan_kamla", artisanName: "Kamla Devi" },
    filterValue: "artisan_kamla",
    linkedWorkshopId: null
  });
  expect(seed.workshopId).toBeUndefined();
  expect(Object.keys(seed).sort()).toEqual(["artisanId", "artisanName"]);
});

test("a cascade with nothing chosen above it seeds no artisan", () => {
  const seed = inlineSeed({
    entity: EXISTING_PRODUCT,
    field: productRefOf(EXISTING_PRODUCT),
    row: {},
    filterValue: "",
    linkedWorkshopId: "ws_1"
  });
  expect(seed.artisanId).toBeUndefined();
});

test("seedHasArtisan is what decides whether the carry banner may still claim the artisan", () => {
  expect(seedHasArtisan(undefined)).toBe(false);
  expect(seedHasArtisan({ workshopId: "ws_1" })).toBe(false);
  expect(seedHasArtisan({ artisanId: "a1" })).toBe(true);
  expect(seedHasArtisan({ artisanName: "Kamla Devi" })).toBe(true);
});

test("the seed is create-only, and the DIALOG is what enforces it", () => {
  /*
    THIS USED TO ASSERT ONE SUBSTRING IN ONE CALLER AND NOTHING ELSE, which is the whole reason it
    needed repairing: `InlineRecordDialog` passed `seed` to all three forms UNCONDITIONALLY, and
    `StageReferenceMultiPicker` passes a bare `seed={seed}`. The single picker's ternary was the only
    gate in the tree, and this test pinned exactly that one line — blind to the two places that had
    the rule wrong, on a spec whose title claims the rule holds.

    THE FORMS CANNOT BE THE GUARD, which is why the assertion moved here: they resolve the seed with
    `??` (`initial?.artisanId ?? seed?.artisanId`), so on a record whose artisan or workshop column
    is NULL — the row this lane exists to let a designer fix — the seed would fill it, the form would
    submit a parent nobody chose, and `useWorkshopSelection` would mark it `touched` so the carry
    banner would not mention it either.
  */
  const dialog = read(DIALOG);

  /*
    ASSERTED AGAINST THE SLICE THAT MOUNTS THE FORMS, not the whole file. The mount moved into
    `InlineRecordForm` so a second host — the design-workshop stage embed — could share it instead of
    copying it, and the dialog now hands its own `seed` down to that component on one line. That line
    is fine: the gate is applied one level lower, immediately above the forms. What must never appear
    is an UNGATED `seed` on a form, and narrowing the search to the mount is what keeps this test
    able to tell those two apart instead of banning the string outright.
  */
  const mount = dialog.slice(
    dialog.indexOf("export function InlineRecordForm"),
    dialog.indexOf("export function InlineRecordDialog")
  );
  expect(mount.length, "InlineRecordForm should be declared above InlineRecordDialog").toBeGreaterThan(500);

  expect(mount, "one gate, computed once from the edit flag it already has").toContain(
    "const seedForForm = editing ? undefined : seed;"
  );
  // All three forms that take a seed take the GATED value. `seed` reaching a form directly is the defect.
  expect(mount.match(/seed=\{seedForForm\}/g)?.length, "Artisan, Product and Tool").toBe(3);
  expect(mount).not.toMatch(/^\s+seed=\{seed\}$/m);
  // And every form is mounted inside that slice, so there is nowhere else a seed could be handed over.
  for (const form of ["<ArtisanForm", "<ProductForm", "<ToolForm", "<ProcessForm"]) {
    expect(mount, `${form} must be mounted by the shared host`).toContain(form);
  }

  // And the two call sites are now belt and braces over that rather than the whole of it.
  const picker = read(PICKER);
  expect(picker).toContain('seed={inlineDialog.mode === "create" ? seed : undefined}');
});

/* ───────────────────────────────────────────────────────────────────────────
 * 5. An empty list explains ITSELF, rather than being read as an empty repository
 * ────────────────────────────────────────────────────────────────────────── */

/** A references answer, defaulting to the shape a healthy WORKSHOP-scoped list comes back in. */
function payload(overrides: Partial<DwReferencePayload> = {}): DwReferencePayload {
  return {
    model: "ProductDocumentation",
    scope: "WORKSHOP",
    scopedToWorkshop: true,
    filtered: false,
    truncated: false,
    options: [{ id: "p1", label: "Sambalpuri saree", sublabel: "" }],
    ...overrides
  } as DwReferencePayload;
}

const WORKSHOP_SCOPED = field({ key: "productRef", refModel: "ProductDocumentation", refScope: "WORKSHOP" });

test("a LINKED workshop with nothing under it says the list is narrowed, not that the repository is empty", () => {
  /*
    THE DEFECT: `ScopeNotice` only ever spoke when the design workshop was UNLINKED. A linked one
    whose workshop record has no artisans, products or tools attached is the commoner case and said
    nothing at all — the picker fell through to its generic "No records to choose from yet.", which
    is a claim about the REPOSITORY when the truth is a claim about the FILTER. A designer reads it
    as "nothing has been documented", closes the picker and types thirty products in by hand, which
    is the exact behaviour the reference feature exists to end.
  */
  const lines = scopeNoticeLines(WORKSHOP_SCOPED, payload({ options: [] }));
  expect(lines.length).toBe(1);
  expect(lines[0]).toContain("narrowed to that workshop");
  // And it must say what to DO, or it is only a better-worded dead end.
  expect(lines[0]).toContain("Create the record here");
});

test("a list that came back with records says nothing at all", () => {
  // The notice is for a designer staring at an empty list. On a full one it is noise on every open.
  expect(scopeNoticeLines(WORKSHOP_SCOPED, payload())).toEqual([]);
});

test("the cascade's own empty list is left to the cascade to explain", () => {
  /*
    `payload.filtered` means the server narrowed to the parent row's record, and the picker's empty
    line already names that row ("That record has nothing documented under it yet"). Two competing
    explanations of one empty list is worse than the generic one, so this stays quiet.
  */
  expect(scopeNoticeLines(WORKSHOP_SCOPED, payload({ options: [], filtered: true }))).toEqual([]);
});

test("an UNLINKED workshop still gets the sentence it always got, and only that one", () => {
  /*
    THE NEGATIVE CONTROL FOR THE NEW BRANCH. Unlinked means the server widened the list to the whole
    table; saying it is "narrowed to that workshop" there would be false, and would send a designer
    looking for a workshop link that is already the thing being complained about.
  */
  const lines = scopeNoticeLines(WORKSHOP_SCOPED, payload({ scopedToWorkshop: false, options: [] }));
  expect(lines.length).toBe(1);
  expect(lines[0]).toContain("not linked to a workshop record yet");
});

test("an ALL-scoped picker is never told anything about workshops", () => {
  // Stage 13's roster picker and every other unscoped REF: an empty list there really is an empty
  // repository, and a sentence about workshop narrowing would be a wrong explanation of a true state.
  const all = field({ key: "artisanRef", refModel: "DwParticipant", refScope: "ALL" });
  expect(scopeNoticeLines(all, payload({ scope: "ALL", options: [] }))).toEqual([]);
});

test("the truncation sentence still comes through, and can share the line", () => {
  // A capped list is a different fact from a narrow one and both can be true at once; neither may
  // silence the other, because a list that quietly stops is this codebase's most repeated bug class.
  const lines = scopeNoticeLines(WORKSHOP_SCOPED, payload({ scopedToWorkshop: false, truncated: true }));
  expect(lines.length).toBe(2);
  expect(lines[1]).toContain("matches are listed");
});

test("the stage page puts the linked workshop within the pickers' reach", () => {
  const page = read("app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx");
  expect(page).toContain("LinkedWorkshopProvider");
  // Off the DRAFT's header, not a fetch: inline creation is used in courtyards with no signal.
  expect(page).toContain("setLinkedWorkshopId(draft.header.workshopId ?? null)");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Cancel
 * ──────────────────────────────────────────────────────────────────────────── */

for (const [name, path] of [
  ["ArtisanForm", ARTISAN_FORM],
  ["ProductForm", PRODUCT_FORM],
  ["ToolForm", TOOL_FORM]
] as const) {
  test(`${name}'s Cancel asks the host to leave rather than popping history`, () => {
    const source = read(path);
    // The exit is one function, so the button and the discard prompt cannot drift apart.
    expect(source).toContain("function leave() {\n    if (onCancel) onCancel();\n    else router.back();\n  }");
    expect(source).toContain("onClick={handleBack}");
  });

  test(`${name} tells its own Cancel apart from the page's back arrow before "Discard" answers`, () => {
    /*
      ── THE DEFECT (wave 3, finding 27) ─────────────────────────────────────────────────────
      Both exits raised the same `UnsavedChangesDialog` and both took the same answer: "Discard"
      ran `resetDirty()` and then `leave()`, which is `onCancel`. In `InlineRecordDialog` that is
      correct — the dialog closes, and the two exits really are one act. In `StageRecordEmbed`
      `onCancel` REMOUNTS THE FORM IN PLACE, because that host is not a route and there is nowhere
      to go. So the designer pressed Back, was asked, answered Discard, LOST EVERYTHING THEY HAD
      TYPED and did not go back; a second press was then needed for the thing they had asked for.

      The flag marks the CANCEL BUTTON rather than the arrow because the arrow's route in is
      `useLeaveGuard`, registered once per mount with a bare callback and no per-press hook.
    */
    const source = read(path);
    expect(source).toContain("    if (dirty) {\n      setPromptFromCancel(true);\n      setBackPromptOpen(true);\n    } else leave();");
    // The guard leaves it alone, which is what makes "set" mean "the Cancel button opened this one".
    expect(source).toContain("useLeaveGuard(dirty, () => setBackPromptOpen(true));");
    // And it is cleared on every answer that TAKES THE PROMPT OFF THE SCREEN — all three here,
    // because this form's Save closes the prompt before submitting. A flag left set outlives the
    // prompt it describes and hands the NEXT back-arrow discard to the wrong exit.
    expect(source.match(/setPromptFromCancel\(false\);/g)?.length, "keep editing, discard, save").toBe(3);
  });

  test(`${name}'s "Discard" completes the exit that asked, and neither one navigates`, () => {
    const source = read(path);
    /*
      The dirty prompt is KEPT in every host — closing the dialog, or remounting the form, still
      throws the typing away, so the question is as load-bearing there as on a page. What changed is
      only what "Discard" does once it has been answered, and that now depends on WHICH control
      asked. A `router.back()` left anywhere but the one fallback inside `leave` is the defect.
    */
    const discard = source.slice(source.indexOf("onDiscard={() => {"), source.indexOf("onSave={() => {"));
    expect(discard, "the form's own Cancel: empty the form, stay put").toContain("if (promptFromCancel) leave();");
    expect(discard, "the host's back arrow: finish what it started").toContain("else leaveAfterDiscard();");
    // `leave` is the only place a CALL to `router.back()` may still stand, and only as the fallback
    // for the page host. A second one anywhere in the file is the defect this test is about. The
    // semicolon is what keeps this off the prose above, which names the function it replaced.
    expect(discard).not.toContain("router.back();");
    expect(source.match(/router\.back\(\);/g)?.length).toBe(1);
  });

  test(`${name} falls back to the ordinary exit when no host supplies the new one`, () => {
    /*
      `onDiscardAndLeave` is OPTIONAL, and the fallback is what keeps the other two hosts and the
      form's own route behaving exactly as they did: on /products/new `leave()` IS the navigation
      the arrow wanted, and in a dialog closing it is the whole of leaving. Without this line an
      absent callback would turn the back arrow's Discard into a silent no-op.
    */
    const source = read(path);
    expect(source).toContain(
      "function leaveAfterDiscard() {\n    if (onDiscardAndLeave) onDiscardAndLeave();\n    else leave();\n  }"
    );
  });
}

test("the second exit is ONE contract member, declared once and consumed by all four forms", () => {
  /*
    THE REASON `forms/inlineRecordHost.ts` EXISTS, applied to the fifth host-wide callback.
    `onCancel`, `onQueued` and `onCreated` were each invented four times, one form at a time, with
    three of the four missing at least one — that file's header opens on exactly that. So this one
    is declared there and reaches the forms through `InlineRecordSurfaceProps`, which is a `Pick`
    off the real contract rather than a second type agreeing with a copy of it.
  */
  const contract = read("components/forms/inlineRecordHost.ts");
  expect(contract).toContain("onDiscardAndLeave?: () => void;");
  expect(contract).toContain(
    'export type InlineRecordSurfaceProps = Pick<InlineRecordHostProps<unknown>, "footerFields" | "onDiscardAndLeave">;'
  );

  // ProcessForm reaches it the same way and spells its own exit differently, because it has no
  // `leave()` — its `onCancel` is required rather than optional.
  const process = read(PROCESS_FORM);
  expect(process).toContain(
    "function leaveAfterDiscard() {\n    if (onDiscardAndLeave) onDiscardAndLeave();\n    else onCancel();\n  }"
  );
  expect(process).toContain("if (promptFromCancel) onCancel();");
  expect(process).toContain("else leaveAfterDiscard();");
  /*
    AND THE GUARD REGISTRATION IS UNTOUCHED, which is not incidental: `discarded-work-unit.spec.ts`
    pins this exact line because it is the one this form spent a release missing, and it is also
    why the flag is set on the CANCEL side. Changing it to carry the exit kind would have traded
    one pinned defect for another.
  */
  expect(process).toContain("useLeaveGuard(dirty, () => setGuardOpen(true));");
  /*
    ALL THREE ANSWERS CLEAR THE FLAG, INCLUDING SAVE, and this count is the assertion that keeps a
    stale flag from bringing the defect back. An earlier version of this line pinned two and said
    ProcessForm's Save "leaves the prompt open (a refused submit keeps it standing)". The form
    contradicts that: `submit()`'s validation refusal calls `setGuardOpen(false)` before it
    returns, and so does every other exit — the late-workshop refusal, the queued branch, the
    partial-media branch, the success branch and the catch. With the flag left set, Cancel → Save →
    refused → fix the field → the HOST'S back arrow sends "Discard" down the Cancel branch, which
    empties the form and stays put. That is the defect `onDiscardAndLeave` exists to end.
  */
  expect(process.match(/setPromptFromCancel\(false\);/g)?.length, "keep editing, discard and save").toBe(3);

  // All four take the member off the shared surface type rather than declaring a fifth spelling.
  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM, PROCESS_FORM]) {
    const source = read(path);
    expect(source, `${path} must destructure the shared callback`).toContain("\n  onDiscardAndLeave,\n");
    expect(source, `${path} must not re-declare it`).not.toContain("onDiscardAndLeave?: () => void;");
    expect(source, `${path} must intersect the shared surface type`).toContain("} & InlineRecordSurfaceProps)");
  }
});

/*
  THE TWO EXITS, ON THE HOST THE DEFECT WAS REPORTED ON.

  `StageRecordEmbed`'s `onCancel` REMOUNTS the form in place, which is the only thing that could
  clear boxes living in React state and uncontrolled DOM. Routing the page's back control into the
  same callback meant a designer pressed Back, answered "Discard", lost everything they had typed
  AND STAYED WHERE THEY WERE — and the single sentence that callback could write had to hedge for
  both exits at once, so the Cancel button carried an instruction about leaving and the arrow
  carried a description of a form being emptied.

  WHAT IS ASSERTED HERE IS THE SPLIT, WHICH IS THE HALF THAT LANDED. The exit is not COMPLETED, and
  the `fixme` under this test is that half, kept as a skip on every run rather than as prose.
*/
test("the stage embed answers its two exits with two different callbacks", () => {
  const embed = read("components/designworkshop/StageRecordEmbed.tsx");
  expect(embed, "StageRecordEmbed must pass onDiscardAndLeave= to InlineRecordForm").toContain(
    "onDiscardAndLeave={handleDiscardAndLeave}"
  );
  // And not by handing it the same callback as Cancel, which is the defect restated.
  expect(embed).not.toContain("onDiscardAndLeave={handleCancel}");
  /*
    TWO CALLBACKS ARE WORTH NOTHING IF THEY SAY THE SAME THING, and the sentence is the only thing a
    designer sees here — nothing about this host visibly changes on either exit except the form
    emptying. So the notices are pinned as DISTINCT SENTENCES, read out of the source rather than
    named.

    THE BLOCKED EXIT'S OWN WORDING IS DELIBERATELY NOT PINNED HERE, and that is a correction. This
    line used to require the phrase "press the same control again", which is a description of a
    TEMPORARY state: the guard banks the refused act, no form answers with `completeLeave()` yet, and
    the sentence exists to admit the extra press. The `fixme` below demands that phrase be GONE once
    the forms answer — so with both assertions standing, the follow-up could not land without turning
    this green test red, and a wave that has to break a passing test to finish is a wave that deletes
    it instead. What both states share is that the two exits must not read alike, and that the Cancel
    one must never tell a designer to press a control again: it is not answering a control at all.
  */
  const cancelText = embed.slice(embed.indexOf("const handleCancel"), embed.indexOf("const handleDiscardAndLeave"));
  const leaveText = embed.slice(embed.indexOf("const handleDiscardAndLeave"), embed.indexOf("const [asked,"));
  const sentence = (slice: string, what: string) => {
    const found = /text: `([^`]+)`/.exec(slice);
    if (!found) throw new Error(`${what} writes no notice of its own`);
    return found[1];
  };
  expect(sentence(cancelText, "the form's own Cancel"), "the two exits must not read alike").not.toBe(
    sentence(leaveText, "the blocked exit")
  );
  expect(cancelText, "the form's own Cancel says nothing about leaving").not.toContain("press the same control again");

  /*
    AND THE SHARED MOUNT FORWARDS IT TO ALL FOUR FORMS. `InlineRecordForm` is the one place either
    host reaches a form, so a member it drops is a member no host can supply — the three-of-four
    threading failure this whole contract exists to prevent, one level up.
  */
  const dialog = read("components/designworkshop/InlineRecordDialog.tsx");
  expect(dialog.match(/onDiscardAndLeave=\{onDiscardAndLeave\}/g)?.length, "one per mounted form").toBe(4);
  /*
    `InlineRecordDialog` ITSELF PASSES NONE, and that is the argued answer rather than an omission:
    its `onCancel` is `onClose`, closing the dialog is the whole of leaving it, and `FieldDialog`
    traps focus and covers the page, so no back control outside the panel can be pressed while the
    form is on screen. The fallback in all four forms (`onDiscardAndLeave ?? leave()`) is therefore
    exactly right there, and a redundant prop would only invite the next reader to make the two
    hosts agree by making the embed's answer the dialog's.
  */
  const mount = dialog.slice(dialog.indexOf("<InlineRecordForm"));
  expect(mount, "the dialog's own mount takes the fallback").not.toContain("onDiscardAndLeave=");
});

/*
  THE REFUSED EXIT SURVIVES THE REFUSAL — the half of the defect above that lives in the guard.

  `useLeaveGuard` does not DELAY a navigation, it REFUSES one: the interceptor returns true and the
  control that tried to leave abandons what it was doing. While nothing carried that act anywhere,
  the form's "Discard" had nothing to resume and neither had its host, so the one answer that means
  "yes, throw it away, I am leaving" delivered the throwing away and not the leaving.

  IT CANNOT BE RECONSTRUCTED AT THE OTHER END, which is why the act travels rather than being
  guessed. On a stage page three controls reach this guard and they want three different things —
  `BackButton`'s `router.back()` or its explicit `href`, the stage page's `leave(action)` for
  "previous stage" / "next stage", and `CollectionTable.toggleRow`, which is not a navigation at all
  — so a `router.back()` invented inside the embed would be right for one, land on the wrong stage
  for the second, and throw a designer off the page for the third, the commonest of the three.

  THIS IS ANCHORED TO STRUCTURE RATHER THAN TO WORDING. A test asserting the absence of a phrase
  goes green for anybody who rewords a notice without touching the guard at all, so what is pinned
  is the TYPE, the four CALL SITES that hand an act over, and the identity rule that decides who may
  finish one. A FOURTH caller has joined the three the handover named: the REF picker, which re-keys
  the embedded record form over a different record and had consulted nothing.
*/
test("the refused exit is carried, and every control that is refused hands one over", () => {
  const guard = read("components/UnsavedChangesGuard.tsx");

  // 1. THE TYPE. A bare `() => boolean` is a refusal that drops what it refused on the floor.
  expect(guard, "LeaveInterceptor must carry the pending act").not.toContain(
    "export type LeaveInterceptor = () => boolean;"
  );
  expect(guard).toContain("export type LeaveInterceptor = (pending: PendingLeave) => boolean;");
  expect(guard).toContain("intercept: (pending: PendingLeave) => boolean;");
  expect(guard, "the hook a control calls takes the act too").toContain(
    "export function useLeaveInterceptor(): (pending: PendingLeave) => boolean {"
  );

  /*
    2. THE PROVIDER HOLDS IT, AND HOLDS WHO REFUSED IT. The identity is not bookkeeping: `complete`
    and `abandon` are answers to a PARTICULAR prompt, so a sibling form's Discard — or a save that
    happens to finish ten minutes later — must not fire a navigation nobody asked for. The same
    identity drops the act when the blocking form unmounts.
  */
  expect(guard).toContain("const held = useRef<{ act: PendingLeave; blockedBy: LeaveInterceptor } | null>(null);");
  expect(guard, "an answer from anyone but the blocker is ignored").toContain(
    "if (!pending || pending.blockedBy !== answeredBy) return;"
  );
  expect(guard, "an exit held for a form that no longer exists can never be answered").toContain(
    "if (held.current?.blockedBy === next) held.current = null;"
  );
  /*
    AND THE RE-ASK EXCLUDES THE FORM THAT ANSWERED. "Discard" clears the dirty flag through React
    state and `complete` runs inside the very click handler that set it, so an interceptor asked
    again would still read `dirty === true` and re-open the prompt it was just dismissed from. Every
    OTHER dirty form is still asked, which is what keeps stage TRADITIONAL_PROCESS_BASELINE — two of
    these forms mounted at once — honest: the second one's prompt, not a silent exit past it.
  */
  expect(guard).toContain("if (entry === skip) continue;");
  expect(guard).toContain("if (!ask(pending.act, answeredBy)) pending.act();");

  /*
    3. EVERY CONTROL HANDS ITS OWN ACT OVER. Read as source rather than driven, because what is
    being pinned is that the act came FROM the control: a test that only proved "something was
    passed" would pass for a `router.back()` written four times.
  */
  const back = read("components/BackButton.tsx");
  expect(back, "the arrow's two destinations are one act, chosen where they are known").toContain(
    "const go = href ? () => router.push(href) : () => router.back();"
  );
  expect(back).toContain("if (interceptLeave(go)) return;");

  const stage = read("app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx");
  // The flush travels WITH the navigation: a resumed "next stage" that skipped `flushLocal` would
  // drop the last seconds of typing on the stage being left.
  expect(stage).toContain("if (interceptLeave(() => void go())) return;");
  /*
    AND THE FLUSH CANNOT SWALLOW THE NAVIGATION. A refused local write already answers `false` and
    this page has always gone anyway — the refusal is marked on the draft and rendered by
    `DraftSyncBanner`, so `action()` runs on both outcomes. A flush that THROWS is the same outcome by
    another route, and the banked path discards its promise (`void go()`), so without the `catch` a
    rejection there would leave the work discarded, the prompt answered, the page unmoved and nothing
    said. Pinned as the whole closure, because the property is the ORDER — `action()` after the
    attempt, inside neither the `try` nor a `finally` that would re-throw past it.
  */
  expect(stage, "the banked act performs the navigation whatever the flush did").toMatch(
    /const go = async \(\) => \{\s*try \{\s*await flushLocal\(\);\s*\} catch \{[\s\S]*?\}\s*action\(\);\s*\};/
  );

  const entity = read("components/designworkshop/EntityForm.tsx");
  // A functional update, because a banked act runs after re-renders the closure never saw.
  expect(entity).toContain("const open = () => setOpenKey((current) => (current === rowKey ? null : rowKey));");
  expect(entity).toContain("if (closing !== null && interceptLeave(open)) return;");

  /*
    THE FOURTH, AND IT IS THE ONE NOBODY HAD GUARDED. `StageRecordEmbed` keys the record form on the
    linked id, so re-pointing this picker — by click, by Enter or by scanning a card — remounts the
    form and takes its React state, its uncontrolled DOM and its staged files with it. Clearing the
    link does the same in the other direction. Both are asked about now, and both are banked.
  */
  const picker = read("components/designworkshop/StageReferenceField.tsx");
  expect(picker, "the picker consults the same guard as the back arrow").toContain(
    'import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";'
  );
  expect(picker, "a pick is asked about only when the pointer would really move").toContain(
    "if (option.id !== selectedId && interceptLeave(() => commitChoice(option))) {"
  );
  expect(picker, "clearing the link remounts the form too").toContain("if (interceptLeave(unlink)) {");
  // A card reader must not announce a write that has not happened; `choose` reports whether it did.
  expect(picker).toContain("if (decision.commit && !chooseNow(decision.commit)) {");
  /*
    AND ALL THREE REFUSALS SAY SO ON THE ROW. The prompt that goes up belongs to a FORM and says
    nothing about this picker, so a refused pick used to be dropped in silence: the designer answered
    "Discard", watched the form empty, and had no reason to doubt the row now named the record they
    had chosen. This is the one assertion in this test that executes rather than reads — the sentence
    is a real exported function, called here with the three retries the three controls pass.
  */
  for (const retry of ["choose the record again", "scan the card again", "press “Clear the link” again"]) {
    expect(picker, `the ${retry} refusal is worded by the shared helper`).toContain(
      `refusedByUnsavedWork("${retry}")`
    );
    const line = refusedByUnsavedWork(retry);
    expect(line.tone, "a refusal is not a confirmation").toBe("warn");
    expect(line.text).toBe(
      `A form on this stage has unsaved work, so this row was left as it was. Answer the prompt on screen, then ${retry}.`
    );
  }
  /*
    TWO CLAIMS IT MUST NOT MAKE, both of which an earlier draft of the scan notice did make.

    IT DOES NOT PROMISE THE PICK LANDS BY ITSELF. Nothing calls `completeLeave()` yet, so "Discard"
    clears the form and the row stays where it was — a sentence saying the scanned record is "chosen
    straight away" put a false line in amber beside the embed's true one in green.

    AND IT DOES NOT SAY WHOSE PROMPT IT IS. The walk asks EVERY registered form, so on stage
    TRADITIONAL_PROCESS_BASELINE the blocker can be a sibling row's form, and on an unlinked
    `mountOnRequest` row there is no form under this picker at all — "the form below this picker"
    sent the designer looking in the wrong place.
  */
  expect(picker, "no notice may promise an exit no form performs yet").not.toContain(
    "the scanned record is chosen"
  );
  expect(picker, "the picker cannot know which form raised the prompt").not.toContain(
    "the record form below this picker has unsaved work"
  );
});

/*
  THE LAST LINK, AND IT IS ONE LINE IN EACH OF FIVE FILES THIS GROUP DOES NOT OWN.

  The guard now banks the act and offers the two calls that finish or forget it —
  `useLeaveGuard(...)` returns `{ completeLeave, abandonLeave }`, both scoped to the form's own
  prompt by identity so neither can fire a navigation belonging to somebody else's. Nothing calls
  them yet, so "Discard" still empties the form and leaves the page where it was, and
  `StageRecordEmbed.handleDiscardAndLeave` still says so out loud — which is the honest state, and
  strictly better than a silent stay.

  WHERE THE CALL GOES, WHICH IS THE WHOLE OF THE CARE THIS CHANGE NEEDS. All four forms answer ONE
  `UnsavedChangesDialog` for TWO questions and tell them apart AFTER the fact, with
  `promptFromCancel`:

      resetDirty();
      if (promptFromCancel) leave();     // the form's own Cancel: "empty this form, I am staying"
      else leaveAfterDiscard();          // a host control's exit: "take me off this screen"

  `resetDirty()` is ABOVE that branch and runs for both, so `completeLeave()` beside it — which is
  what an earlier draft of this marker asked for — would fire a banked `router.back()`, stage push or
  row collapse off the form's own Cancel. That is exactly the defect `promptFromCancel` was added to
  prevent, so the assertions below pin the `else` BRANCH and refuse the placement above it rather
  than accepting the substring anywhere in the file. `abandonLeave()` belongs in the "Keep editing"
  handler for the matching reason: an answer that means "stay" must not leave an act in `held` for
  the next unrelated Discard to run.

  WHICH WAY ROUND THE TWO CALLS GO IN THAT BRANCH IS THE FOLLOW-UP'S TO DECIDE, so both orders pass.
  `completeLeave()` may perform a navigation, after which the host's notice is moot; that is the same
  question as the last line of this test — once the exit really completes, `StageRecordEmbed`'s "press
  the same control again" is describing something that no longer happens, and it and
  `StageReferenceField`'s `refusedByUnsavedWork` are the two sentences that go stale together.

  The wording checks are LAST and are not the whole of the bar, for the reason the previous marker
  here recorded: a fixme asserting only the absence of a phrase goes green for anybody who rewords
  the notice without touching a thing.
*/
test.fixme("the record forms answer the prompt with the act the guard is holding", () => {
  const guard = read("components/UnsavedChangesGuard.tsx");
  const embed = read("components/designworkshop/StageRecordEmbed.tsx");
  expect(guard).toContain("return { completeLeave, abandonLeave };");
  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM, PROCESS_FORM]) {
    const source = read(path);
    // IN THE BRANCH THAT MEANS "LEAVE", beside the host callback and nowhere else.
    expect(source, `${path} must finish the exit in the branch that means "leave"`).toMatch(
      /else \{\s*(completeLeave\(\);\s*leaveAfterDiscard\(\);|leaveAfterDiscard\(\);\s*completeLeave\(\);)\s*\}/
    );
    // AND NOT ABOVE THE TEST THAT TELLS THE TWO EXITS APART, where it would also fire on Cancel.
    const discard = source.slice(source.indexOf("onDiscard={() => {"));
    const beforeTheBranch = discard.slice(0, discard.indexOf("if (promptFromCancel)"));
    expect(beforeTheBranch, `${path} must not complete the exit on its own Cancel`).not.toContain("completeLeave()");
    // AND THE ANSWER THAT MEANS "STAY" FORGETS IT, in that handler rather than somewhere in the file.
    const keepEditing = source.slice(source.indexOf("onKeepEditing={() => {"));
    expect(keepEditing.slice(0, keepEditing.indexOf("}}")), `${path} must forget the act on "Keep editing"`).toContain(
      "abandonLeave()"
    );
  }
  // Both sentences that describe the extra press go with it — see the paragraph above.
  expect(embed).not.toContain("press the same control again");
  expect(read(PICKER), "the picker no longer tells a designer to press its control again").not.toContain(
    "Answer the prompt on screen, then"
  );
});

test("no record form titles a screen its host has already titled", () => {
  /*
    `ProcessForm` was the only one of the four that drew a heading of its own, written when
    /processes was the only host. ALL THREE HOSTS TITLE THE SURFACE THEMSELVES, and the page — the
    host the heading was written for — does it with the SAME TWO STRINGS: `PageHeader` renders an
    `<h1>` of "Document process" / "Edit process" directly above a form that painted "Document
    process" / "Edit process". `InlineRecordDialog` passes `FieldDialog` a title, and
    `StageRecordEmbed` mounts the form inside a stage entity panel whose `EntityForm` renders the
    entity's own `<h2>` above it.

    IT WAS FIRST SUPPRESSED ONLY WHEN HOSTED, on `Boolean(onCreated)`, which closed the loud case
    (stage 5's two sibling `h2`s — the heading list is one of the two ways a 22-stage form is
    navigated by a screen reader, and a duplicate rung describes a structure the page does not
    have) and left the page's own duplicate title standing. `h1` then `h2` is at least a real
    outline, so it was quieter, not absent. The heading is now gone everywhere and the flag with
    it, which is why this asserts across all four forms rather than three.
  */
  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM, PROCESS_FORM]) {
    expect(read(path), `${path} has grown a heading its host already draws`).not.toContain("<h2 ");
  }
  const source = read(PROCESS_FORM);
  // No flag left over, either: it read the heading and nothing else.
  expect(source).not.toContain("const hosted = Boolean(onCreated);");
  // The PARAGRAPH under it stays on every host: it says what a process record is, which no host
  // repeats. Only the heading was a claim about the screen.
  expect(source).toContain("Capture how a product is made, step by step.");
});

test("the one capture card that outlives its own mount names a staging owner that does too", () => {
  /*
    `useEagerStaging`'s owner is per-mount by default, and on unmount `lib/media` aborts the
    transfer and DELETES the object already in storage two seconds later. That is right wherever the
    card's lifetime is the files' lifetime, and wrong for `ProcessForm`'s pre-process card: it is
    mounted only while "Pre-processes available" is ticked, while `preFiles` lives one level up in
    the form. Unticking therefore binned photographs the re-tickable box still held.

    WHAT IT BUYS IS BOUNDED AT TWO SECONDS, and this test would otherwise read as though the hazard
    were closed. Unticking still unmounts the card and still releases the owner; the stable name
    helps only because `stageFiles` cancels a pending release for the same owner, and `lib/media`'s
    `RELEASE_GRACE_MS` is 2_000. Re-tick inside that window and the transfer survives; re-tick ten
    seconds later and the object is already deleted and the files upload again from scratch. The
    FILES are never lost either way — `preFiles` is hoisted into the form — so what the longer gap
    costs is the upload, not the work. Closing it is a separate change (keep the card mounted and
    hidden while unticked, or do not release an owner whose file list is non-empty).

    IT IS HALF OF A PAIR AND THE OTHER HALF IS WHY THE REST OF THE FILE HAS NO OWNER KEY. A stable
    owner keeps the OBJECT alive; the hoisted file list keeps the BROWSER's reference. Shipping the
    first without the second is worse than shipping neither — the object survives with nothing able
    to link it — and in the other capture cards the `File[]` is destroyed by the very remount that
    would release the owner. `useEagerStaging`, `StagePendingMediaProvider` and `inlineRecordHost`
    all say so in the same words; this test is the third place that would go red if a later sweep
    "completed" the pattern across the four forms.
  */
  const source = read(PROCESS_FORM);
  expect(source).toContain("stagingOwnerId={`${formId}:pre-process`}");
  // Exactly one, and it is that card. The step cards and the other three forms' cards keep the
  // per-mount default, which is correct for them.
  expect(source.match(/stagingOwnerId=/g)?.length, "the pre-process card and nothing else").toBe(1);
  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM]) {
    expect(read(path), `${path} must not pin a staging owner without hoisting its file list`).not.toContain(
      "stagingOwnerId="
    );
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2b. The three premises the refusal above rests on
 *
 * `inlineRecordHost.ts` refuses BOTH halves of the hoisted-list / stable-owner pair on these four
 * forms, and the second refusal (2026-08-22) is the stronger one: hoisting the `File[]` above the
 * embed's remount is not merely insufficient here, it is WRONG, because every remount these forms
 * get means "throw the attachments away". That conclusion is an argument, and an argument is only
 * as good as the facts under it — all three of which live in files the contract does not own and
 * could be changed by someone who never reads it.
 *
 * SO THE FACTS ARE PINNED HERE RATHER THAN THE CONCLUSION. Each test below fails if a premise moves,
 * which is the signal to re-open the decision rather than to discover months later that the reason
 * for it evaporated. They are deliberately NOT assertions that the store does not exist: building it
 * is allowed, once one of these has changed to make it safe.
 *
 * WHAT THEY CANNOT DO is the limit named at the top of this file — they read source, so they pin the
 * spelling of a line and not its effect. Nothing here proves an object was or was not deleted from
 * storage; that needs the stack, and `inline-record-create.spec.ts` is where a browser is driven.
 * ──────────────────────────────────────────────────────────────────────────── */

const EMBED = "components/designworkshop/StageRecordEmbed.tsx";
const ENTITY_FORM = "components/designworkshop/EntityForm.tsx";
const MEDIA_LIB = "lib/media.ts";
const CONTRACT = "components/forms/inlineRecordHost.ts";

test("premise 1: attaching a file is unsaved work, so a collapsing row asks before it unmounts", () => {
  /*
    THE ONE UNMOUNT THAT IS AN ACCIDENT RATHER THAN A DECISION is a collection row folding up under
    an open record form, and it is the whole of what a hoisted file list would have been for. The
    contract's answer is that it is already guarded: the form marks itself dirty the moment a file is
    attached, and `CollectionTable.toggleRow` asks before it closes anything, so the panel is not
    unmounted at all unless the designer answers Discard — which means what it says.

    BOTH HALVES OR NEITHER IS A GUARD. A form that stopped counting attached files as unsaved work
    would collapse silently over them, and `useEagerStaging` would then delete the object already in
    object storage about two seconds later — the exact defect `StagePendingMediaProvider` was written
    for, on the surface that decided it did not need one.
  */
  for (const path of [ARTISAN_FORM, PRODUCT_FORM, TOOL_FORM]) {
    const source = read(path);
    // Every capture card in these three answers with an inline arrow, so slicing on it reaches all
    // of them — including the grid-measurement card, whose files are form state on the same footing.
    const handlers = source.split("onFilesChange={(files) => {").slice(1);
    expect(handlers.length, `${path} draws at least one capture card`).toBeGreaterThan(0);
    handlers.forEach((handler, index) => {
      expect(handler.slice(0, 200), `${path} capture card ${index + 1} must mark the form dirty`).toContain(
        "markDirty();"
      );
    });
  }

  /*
    `ProcessForm` answers the same question differently and has to be read differently: its guard is
    a SIGNATURE diff rather than an `onDirty` event, so its cards pass `setPreFiles` and an
    `updateStep` call with no `markDirty` anywhere. The two counts below are where an attached file
    enters that signature; drop either and the form goes quiet about a photograph in exactly the way
    the three above would.
  */
  const process = read(PROCESS_FORM);
  expect(process, "ProcessForm counts pre-process files into its dirty signature").toContain("pre: preFiles.length");
  expect(process, "ProcessForm counts each step's files into its dirty signature").toContain("files: step.files.length");

  // And the control that does the asking. Without this line the collapse never reaches a form.
  expect(read(ENTITY_FORM), "a collapse consults the forms mounted inside the row").toContain(
    "if (closing !== null && interceptLeave(open)) return;"
  );
});

test("premise 2: a save leaves the attached files in place, so hoisting them would link them twice", () => {
  /*
    THE REASON HOISTING IS WRONG HERE AND NOT MERELY INSUFFICIENT. `StageRecordEmbed` re-keys the
    form on every save, and none of the four clears its `File[]` on the way through: the list is
    destroyed by the remount, which is the cleanup. Hoist it above the remount and those same
    photographs land in the fresh edit-mode mount, where the next Save uploads and links every one of
    them a second time — a duplicate in a ministry report rather than a missing file.

    IF A FORM EVER DOES CLEAR ITS LIST ON THE SAVE PATH, this premise is gone and the pair is worth
    re-measuring. That is the point of failing here rather than silently continuing to be right for a
    reason that stopped being true.
  */
  for (const [path, writes] of [
    [ARTISAN_FORM, ["setMediaFiles("]],
    [PRODUCT_FORM, ["setMediaFiles(", "setGridFiles("]],
    [TOOL_FORM, ["setMediaFiles(", "setStageFiles(", "setGridFiles("]],
    // `ProcessForm` holds a `File[]` PER STEP as well, inside `steps`, and uploads them one at a
    // time. Those files are in premise 1 (`files: step.files.length` is in its dirty signature), so
    // they have to be in premise 2 or the two premises disagree about what they cover: the writers
    // are `setSteps`/`updateStep`, and the value either of them assigns is `files: []`.
    [PROCESS_FORM, ["setPreFiles(", "setSteps(", "files: []"]]
  ] as const) {
    const source = read(path);
    /*
      BOTH BRANCHES OF THE SAVE, WHICH IS WHY THIS STARTS AT THE QUEUED TEST AND NOT AT THE RECORD.
      An earlier revision sliced from `const saved = outcome.saved;`, i.e. from AFTER the
      `if (outcome.queued)` early return — and that branch is a save path too, the OFFLINE one, where
      the files have just been copied into IndexedDB by `saveOrQueue` and clearing the list is the
      natural next thing for a future author to write. Premise 2 would have been false with this test
      still green. `if (outcome.queued) {` sits immediately above the record line in all four forms,
      so one contiguous slice covers both; the assertion below refuses a slice that has lost either
      end rather than quietly reading half of one.

      The catch is searched FROM the queued marker, not from the top of the file: `ProcessForm` has an
      earlier `catch (err)` in an unrelated helper, and slicing to that one silently reads nothing.
    */
    const start = source.indexOf("if (outcome.queued) {");
    const savePath = source.slice(start, source.indexOf("} catch (err) {", start));
    expect(start, `${path} has a queued branch to read`).toBeGreaterThan(-1);
    expect(savePath, `${path}'s slice must reach the online branch as well`).toContain(
      "const saved = outcome.saved;"
    );
    expect(savePath.length, `${path} has a save path to read`).toBeGreaterThan(200);
    for (const write of writes) {
      /*
        THE SETTER NAME AND NOT A GUESSED LITERAL. These guards used to read `setGridFiles([`, and
        `gridFiles` is not an array: `GridFiles` is `Partial<Record<GridGroup, File>>`, so a clear
        would be written `setGridFiles({})` and the guard could never match — green over exactly the
        violation it existed to catch, on the two forms that hold the grid-measurement photographs.
        Every legitimate call to all of these setters is in an `onFilesChange` handler in the JSX,
        far below the catch this slice ends at, so the narrower literal was buying nothing anyway.
      */
      expect(savePath, `${path} must not clear its file list on the save path`).not.toContain(write);
    }
  }

  // And the remount that would otherwise inherit them. Both discards bump the same generation.
  const embed = read(EMBED);
  expect(embed, "the form is keyed on the generation as well as the linked record").toContain(":${formGeneration}`}");
  expect(embed).toContain("const remountForm = useCallback(() => setFormGeneration((generation) => generation + 1), []);");
  // FOUR SITES, THREE REASONS, AND NO CREATE AMONG THEM — `StageRecordEmbed`'s own paragraph on
  // `remountForm` spells the mismatch out: the create path re-keys through the linked id instead, the
  // edit path calls this twice (the re-described row and `adoptEdited`'s could-not-describe branch),
  // and each discard callback calls it once.
  expect(embed.match(/remountForm\(\);/g)?.length, "the edit path twice, and the two discards").toBe(4);
});

test("premise 3: the eagerly-uploaded objects are claimed before any remount can release them", () => {
  /*
    WHY TODAY'S BEHAVIOUR IS A CLEANUP AND NOT A LOSS, which is the sentence the whole refusal turns
    on. `releaseStagedOwner` deletes an owner's unclaimed objects after `RELEASE_GRACE_MS`, and the
    post-save remount releases the form's per-mount owner — but by then the objects the save cared
    about are no longer in the store to delete, because both upload entry points claim them
    SYNCHRONOUSLY, before their first `await`.

    Move either claim behind an await and the post-save remount starts racing the save it is supposed
    to follow: the release timer would find records still in the store and bin objects the save was
    about to link. That is a data-loss bug on its own, and it would also make "the files die with the
    mount, which is correct" false.

    ── THE SLICES ARE BOUNDED AND THE COMMENTS ARE STRIPPED, AND BOTH WERE REAL HOLES ─────────────
    An earlier revision sliced from each signature to END OF FILE and searched the raw text for
    `"await "`. Both halves of that were wrong in the same direction — quietly satisfiable.
    `uploadMediaFile`'s body contains no `await` at all (it returns `linkOrUpload(...)`), so an
    unbounded slice found an await belonging to some LATER function and compared two positions in two
    different functions; and the substring matched PROSE, so `lib/media`'s own comment "before the
    first await, so nothing can reclaim…" escapes only because a comma follows the word rather than a
    space. Rewording that sentence would have turned this red for no behavioural reason. So: cut each
    body at the closing brace in column 0, and strip comments before looking for the keyword.
  */
  const media = read(MEDIA_LIB);

  /**
   * One function's body, from the line that closes its parameter list to the `}` in column 0 that
   * ends it, with comments blanked out.
   *
   * Comments are BLANKED RATHER THAN DELETED (replaced by spaces of the same length) so that every
   * index this test compares still points at the same character of the real file — the failure
   * message a future reader gets is about the code, not about an offset in a rewritten copy.
   */
  function bodyOf(signatureClose: string, from: number): string {
    const open = media.indexOf(signatureClose, from);
    expect(open, `lib/media.ts still declares ${signatureClose}`).toBeGreaterThan(-1);
    const close = media.indexOf("\n}\n", open);
    expect(close, `${signatureClose}'s body still ends at a brace in column 0`).toBeGreaterThan(open);
    return media
      .slice(open, close)
      .replace(/\/\*[\s\S]*?\*\//g, (run) => run.replace(/[^\n]/g, " "))
      .replace(/\/\/[^\n]*/g, (run) => " ".repeat(run.length));
  }

  const batchAt = media.indexOf("export async function uploadMediaBatch({");
  const body = bodyOf("}): Promise<BatchResult> {", batchAt);
  const claim = body.indexOf("const records = takeStagedFor(files);");
  const firstAwait = body.search(/\bawait\b/);
  expect(claim, "uploadMediaBatch claims the staged objects").toBeGreaterThan(-1);
  expect(firstAwait, "uploadMediaBatch does await something").toBeGreaterThan(-1);
  expect(claim, "the claim must come before the first await").toBeLessThan(firstAwait);

  /*
    `ProcessForm` uploads one file at a time through the other entry point, so it needs the same
    guarantee — and there it is stated the only way it can be, because that body has no `await` to
    come before: the claim is the FIRST STATEMENT of the function. Anything inserted above it is
    either an await (the hazard) or a statement that could grow into one, and both are worth a red
    test. This is the assertion the dead `if (singleAwait > -1)` guard was pretending to make.
  */
  const singleAt = media.indexOf("export async function uploadMediaFile({");
  const singleBody = bodyOf("}) {", singleAt);
  const firstStatement = singleBody.split("\n").find((line) => line.trim().length > 0 && !line.startsWith("}") );
  expect(firstStatement?.trim(), "uploadMediaFile claims the staged object first, above everything").toBe(
    "const [record] = takeStagedFor([file]);"
  );
  expect(singleBody.search(/\bawait\b/), "uploadMediaFile still has no await of its own to race").toBe(-1);

  // The contract points at these three premises by name, so a reader who reaches the refusal can
  // find out whether it still holds instead of taking it on trust.
  expect(read(CONTRACT), "the contract names where its premises are pinned").toContain(
    "inline-record-host-unit.spec.ts"
  );
});

test("the contract no longer describes a handoff none of the four forms implements", () => {
  /*
    `InlineRecordHostProps.onCreated`'s prose said the four forms "set the banner, stay mounted, and
    call this from a button beside it, once the message has been read". No form has ever had that
    button: all four set the partial-upload banner and call the callback inline, and each argues for
    it beside the line. The argument is right — a record that exists over a row nobody linked is the
    silent loss, and a create-mode form left standing is a duplicate waiting to be pressed — so the
    CONTRACT was corrected rather than the code. A contract that lies is worse than one that is
    silent, and this is the assertion that keeps the fiction from coming back.
  */
  const contract = read("components/forms/inlineRecordHost.ts");
  expect(contract).toContain("THIS PARAGRAPH USED TO DESCRIBE A HANDOFF NOBODY IMPLEMENTED");
  expect(contract).not.toContain("call this from a button beside it, once the message has been read");
  // And the way forward, so it is a position rather than a shrug.
  expect(contract).toContain("report the failures ALONGSIDE the record");

  // The forms really do call it inline, which is the half the prose now describes.
  for (const [path, callback] of [
    [ARTISAN_FORM, "if (onCreated) onCreated(saved);"],
    [PRODUCT_FORM, "if (onCreated) onCreated(saved);"],
    [TOOL_FORM, "if (onCreated) onCreated(saved);"]
  ] as const) {
    expect(read(path), `${path} reports the record from the partial-failure branch`).toContain(callback);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The offline create
 * ──────────────────────────────────────────────────────────────────────────── */

for (const [name, path] of [
  ["ArtisanForm", ARTISAN_FORM],
  ["ProductForm", PRODUCT_FORM],
  ["ToolForm", TOOL_FORM],
  ["ProcessForm", PROCESS_FORM]
] as const) {
  test(`${name} reports a queued save to its host instead of returning silently`, () => {
    const source = read(path);
    expect(source).toContain("onQueued");
    // The branch itself, not merely the prop: the three page-hosted forms used to `return` straight
    // after scrolling to a banner that a dialog host cannot show.
    const queued = source.slice(source.indexOf("outcome.queued"));
    expect(queued.slice(0, 2000)).toMatch(/if \(onQueued\)/);
  });
}

test("the picker says a queued record was saved, is unlinked, and what to do next", () => {
  const source = read(PICKER);
  const notice = source.slice(source.indexOf("const QUEUED_OFFLINE_NOTICE"), source.indexOf("Single select"));
  // Three claims, all load-bearing: saved (or they create a duplicate), NOT linked (or they submit
  // the stage believing it is), and the way out.
  expect(notice).toContain("saved on this device");
  expect(notice).toContain("nothing could be linked here");
  expect(notice).toContain("choose it then");
  // Both pickers say it, so a designer meets one explanation of this state rather than two.
  expect(source.match(/QUEUED_OFFLINE_NOTICE/g)?.length).toBeGreaterThanOrEqual(3);
});

test("no placeholder id is ever written into the REF field", () => {
  /*
    A REF must hold a real server id: `hydrate_entries`, `canonical_divergence` and the report's
    `ReferencedRecord` join all resolve on it, so a client-invented id would render for ever as a
    reference to a deleted record. The queued handler supersedes the pending hydration and writes a
    notice; it must not call `onChange` with anything.
  */
  const source = read(PICKER);
  const handler = source.slice(source.indexOf("onQueued={() => {"));
  expect(handler.slice(0, 200)).toContain("supersede();");
  expect(handler.slice(0, 200)).not.toContain("onChange(");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The duplicate prompt
 * ──────────────────────────────────────────────────────────────────────────── */

test("the duplicate prompt hands the existing artisan back when there is a host to hand it to", () => {
  const source = read(ARTISAN_FORM);
  const handler = source.slice(source.indexOf("onOpenExisting={() => {"), source.indexOf("onDiscard={discardEntry}"));
  expect(handler).toContain("if (onUseExisting) {\n            onUseExisting(conflict.existingArtisan);\n            return;\n          }");
  // The page host keeps its navigation, which is right there.
  expect(handler).toContain("router.push(`/artisans/${conflict.existingArtisan.id}/edit`)");
});

test("nothing from the conflict payload reaches the row", () => {
  /*
    `ArtisanIdentityConflict` carries `maskedValue` — a masked Aadhaar or Pehchan string — and a
    masked identity number must never be written onto a stage entry. The picker's handler may read
    the id and the name and nothing else, and the name only as the term `describeCreated` searches
    with; every value that lands on the row comes back from the server's own payload.
  */
  const source = read(PICKER);
  const handler = source.slice(source.indexOf("onUseExisting={(artisan) => {"));
  expect(handler.slice(0, 300)).toContain("{ id: artisan.id, name: artisan.name }");
  expect(handler.slice(0, 300)).not.toContain("maskedValue");
  // And the record handed on is the two-key object above and nothing wider: `adoptCreated` reads
  // only `record.id` and `createdLabel(record)`, so widening this object is the only way a masked
  // number could ever get near `hydrateFromReference`.
  expect(handler.slice(0, 300)).not.toContain("conflict");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The dialog wires all four, for all four models
 * ──────────────────────────────────────────────────────────────────────────── */

test("every form gets a way out that is not a navigation, for all four models", () => {
  /*
    THE MOUNT MOVED AND THE RULE DID NOT. The four forms used to be mounted directly by
    `InlineRecordDialog`; they are now mounted by `InlineRecordForm` in the same file, because a
    SECOND host — the design-workshop stage embed — needs the identical mount and a copy of it is
    how three of these four callbacks came to be missing from at least one form in the first place
    (see this file's header and `forms/inlineRecordHost.ts`).

    So the counts are asserted where the forms actually are. All four take the HOST's cancel and the
    HOST's queued callback: without `onCancel` the forms fall back to `router.back()`, which in a
    dialog pops the real history entry and in the stage embed abandons the stage being filled in.
  */
  const source = read(DIALOG);
  // Four forms, four cancels. `ProcessForm` needs `onDone` as well because it is embedded on its own
  // page too, and both must be the host's — they are the paths with no record to report.
  expect(source.match(/onCancel=\{onCancel\}/g)?.length, "one per form").toBe(4);
  expect(source.match(/onDone=\{onCancel\}/g)?.length, "ProcessForm's second way out").toBe(1);
  expect(source.match(/onQueued=\{onQueued\}/g)?.length, "one per form").toBe(4);
  expect(source.match(/onCreated=\{onCreated\}/g)?.length, "one per form").toBe(4);
  // The GATED seed, three times — see "the seed is create-only" above for why the gate is one line
  // in this file and not a ternary at each call site.
  expect(source.match(/seed=\{seedForForm\}/g)?.length).toBe(3);
  expect(source).toContain("onUseExisting={onUseExisting}");
  // Every form gets the host's extra-fields slot, so the stage embed can put the workshop's own
  // questions at the bottom of any of the four. A form missing it silently drops them.
  expect(source.match(/footerFields=\{footerFields\}/g)?.length, "one per form").toBe(4);
});

test("the DIALOG still turns the host callbacks into ones that close it", () => {
  /*
    The two hosts differ in exactly one way and this is it: a dialog is done with the form once it
    has saved, and the stage embed is not — the row it belongs to is still being filled in. So the
    closing behaviour lives here, wrapped around the shared mount, rather than inside it.
  */
  const source = read(DIALOG);
  expect(source).toContain("onCreated={finish}");
  expect(source).toContain("onCancel={onClose}");
  expect(source).toContain("onQueued={reportQueued}");
  expect(source).toContain("onUseExisting={onUseExisting ? adoptExisting : undefined}");
  // `finish` and `reportQueued` are the two that close; `adoptExisting` is the third.
  for (const closing of ["onCreated(record);\n      onClose();", "onQueued?.();\n    onClose();"]) {
    expect(source, "a host callback that no longer closes the dialog").toContain(closing);
  }
  // NO `footerFields` FROM THE DIALOG: it has no questions of its own to add. That slot exists for
  // the stage embed, which really does ask a few things the repository record does not hold.
  expect(source).not.toContain("footerFields={<");
});

test("the DIALOG's own two exits ask before they discard the form inside it", () => {
  /*
    THE ONE EXIT NOBODY HAD WIRED TO THE LEAVE INTERCEPTOR. Everything else on this dialog was
    guarded — `dismissOnBackdrop={false}` refuses a stray click beside the panel, and the form's own
    Cancel raises the form's own prompt — while `FieldDialog`'s document-level Escape handler and its
    × both ended in a bare `onClose()`. So one keypress over a half-typed artisan discarded the
    typing, the attached `File[]`, and (about two seconds later, when `releaseStagedOwner` fires) the
    objects already eagerly uploaded to storage. It is the same class of loss the whole
    leave-interceptor lane exists to end, arriving by the one key nothing consulted.

    THE OTHER FOUR CALLBACKS MUST STAY UNGUARDED, and that is asserted rather than left to reading:
    `finish`, `reportQueued` and `adoptExisting` all run AFTER a write, and `onCancel` runs after the
    form's own prompt has already been answered — where `resetDirty()` has cleared the flag through
    React state from inside the very handler that would re-ask, so the interceptor would still read
    `dirty === true` and re-open the prompt it was just dismissed from.
  */
  const source = read(DIALOG);
  expect(source, "the dialog asks the guard the same way every other guarded control does").toContain(
    'import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";'
  );
  expect(source, "the close is refused when a form has taken responsibility for it").toMatch(
    /const closeUnlessAsked = useCallback\(\(\) => \{\s*if \(interceptLeave\(onClose\)\) return;\s*onClose\(\);\s*\}/
  );
  // Handed to `FieldDialog`, which is what routes BOTH the × and Escape through it.
  expect(source, "FieldDialog's own two exits go through the guarded close").toContain("onClose={closeUnlessAsked}");
  // And the four that must not: a guarded `onCancel` in particular is an infinite prompt.
  expect(source).toContain("onCancel={onClose}");
  expect(source).toContain("onCreated={finish}");
  expect(source).toContain("onQueued={reportQueued}");
  expect(source, "a post-write callback must not be routed through the guard").not.toContain(
    "onCancel={closeUnlessAsked}"
  );
  // The backdrop is still refused outright — the guard is an extra question, not a replacement.
  expect(source).toContain("dismissOnBackdrop={false}");
  /*
    AND THE SENTENCE THAT USED TO DESCRIBE THE GAP AS A FEATURE. The comment beside
    `dismissOnBackdrop` read "The close control and Escape both still work", which is true and reads
    as reassurance; it now says they ask first. Asserting the correction keeps a later reader from
    restoring the shorter, more comfortable version.
  */
  expect(source).toContain("THE CLOSE CONTROL AND ESCAPE BOTH STILL WORK, and they now ASK FIRST");

  /*
    THE OTHER HALF LIVES IN `FieldDialog` AND IS DELIBERATELY NOT CHANGED THERE. Its Escape handler
    calls whatever `onClose` it was handed, with no opinion about guards — which is right for the
    dozen dialogs that hold no typing (`ConfirmDialog`, `RecordCode`, the rest), and is why the fix
    belongs in this host rather than in the shared primitive. This assertion says so, so that a
    future reader who finds the bare call there does not "fix" it for every dialog in the app.
  */
  expect(read("components/dialogs/FieldDialog.tsx"), "FieldDialog stays unopinionated about guards").not.toContain(
    "useLeaveInterceptor"
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * The measurement-grid marker — a three-surface contract
 * ──────────────────────────────────────────────────────────────────────────── */

test("the grid marker is the exact string the server and the handset agree on", () => {
  /*
    The web writes it, Android writes the identical string, and the server SORTS any candidate
    carrying it last (a sort key in `_reference_photos`, never a `WHERE` — nothing is excluded into a
    blank). Change it in one place and nothing errors: the ministry report just starts printing ruled
    graph paper captioned as the tool again, which is what the oldest-first rule did before the
    marker existed, because a grid shot is the first photograph most products ever get.

    IT IS A RESTATEMENT OF THE CONSTANT'S OWN DEFINITION, and that is admitted rather than dressed
    up. Its value is as one end of a three-surface pin — `MeasurementGridMarkerTest.kt` holds the
    mirror image and the server names the same literal — so a rename shows up as two red tests
    instead of a silently broken contract. What it CANNOT catch is the web writing the right string
    into the wrong place, which is the failure that would actually reach a report; the two placement
    tests below are what cover that.
  */
  expect(MEASUREMENT_GRID_PURPOSE).toBe("MEASUREMENT_GRID");
});

for (const [name, path] of [
  ["ProductForm", PRODUCT_FORM],
  ["ToolForm", TOOL_FORM]
] as const) {
  test(`${name} marks its grid photographs on both the online and the queued path, and marks nothing else`, () => {
    /*
      ASSERTED BY PLACEMENT, NOT BY A COUNT. This test used to be
      `expect(source.match(/extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE }/g)?.length).toBe(2)`
      — which passes with both literals sitting in the WRONG upload call, and the wrong call is the
      only interesting way to get this wrong: writing the marker onto the field-media batch would
      sort the researcher's real catalogue photographs behind the graph paper, which is the printed
      defect this whole marker exists to end, inverted.

      TWO PLACES, because a marker written on one leaves half the fleet printing graph paper: the
      `saveOrQueue` media batch (offline is the ordinary case in a village) and the `uploadMediaFile`
      loop that runs when the save went straight to the server.
    */
    const source = read(path);
    const marker = /extraMetadata: \{ purpose: MEASUREMENT_GRID_PURPOSE \}/;

    // The queued batch: the entry built from `gridFiles`, up to where the field-media entry begins.
    const queuedGrid = source.slice(
      source.indexOf("...(Object.entries(gridFiles) as [GridGroup, File][]).map("),
      source.indexOf("files: mediaFiles,")
    );
    expect(queuedGrid, "the queued grid entry carries the marker").toMatch(marker);

    // The online loop: the awaited `uploadMediaFile` per grid file, up to the field-media batch.
    const onlineGrid = source.slice(
      source.indexOf("for (const [group, file] of Object.entries(gridFiles)"),
      source.indexOf("if (mediaFiles.length) {")
    );
    expect(onlineGrid, "the online grid upload carries the marker").toMatch(marker);

    // AND NOWHERE ELSE. Everything outside those two windows — both field-media uploads above all —
    // must be free of it, or the record's real photographs get sorted behind the calibration shot.
    const elsewhere = source.split(queuedGrid).join("").split(onlineGrid).join("");
    expect(elsewhere, "no other upload on this form is marked as a measurement grid").not.toMatch(marker);
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * The leave guard, which the dialog made into a stack
 * ──────────────────────────────────────────────────────────────────────────── */

test("the unsaved-changes guard holds a stack, and a form only ever disarms itself", () => {
  /*
    `UnsavedChangesProvider` held ONE slot under the reasoning "only one form is ever on screen at a
    time". `InlineRecordDialog` made that false: it mounts a record form — all four of which call
    `useLeaveGuard` — on top of whatever page opened it, which may itself be a guarded form. The
    inner form overwrote the outer one on mount, and its cleanup ran `register(null)` unconditionally
    on unmount, so opening the dialog once and closing it left the page underneath with NO
    interceptor for the rest of its life: its back arrow silently stopped warning about unsaved work.

    THE STAGE PAGE NOW HITS IT, which it did not when this test was written. Four of its entities
    embed a repository record page, so the page hosts one or two of those four forms directly —
    stage TRADITIONAL_PROCESS_BASELINE is the two, because `traditionalProcess` is a mirror-point
    singleton and `tool` a mirror-point collection.
  */
  const guard = read("components/UnsavedChangesGuard.tsx");
  expect(guard).toContain("register: (interceptor: LeaveInterceptor) => () => void;");
  expect(guard).toContain("const interceptors = useRef<LeaveInterceptor[]>([]);");
  /*
    ASKED INNERMOST FIRST, UNTIL ONE BLOCKS — and it used to be "the topmost answers, full stop".
    That was right while the stack could only be a page under a dialog. It is wrong for two SIBLING
    forms on one page, which the stage embed made possible: a dirty `ProcessForm` plus a freshly
    opened, clean `ToolForm` row meant the back arrow asked the tool form, was told there was
    nothing to save, and navigated with the half-typed process gone and no prompt. The walk STOPS at
    the first blocker, because returning true means that form has already put its dialog on screen
    and asking the rest would stack a second over it.
  */
  expect(guard).toContain("for (let index = stack.length - 1; index >= 0; index -= 1) {");
  /*
    THE BLOCKER IS ASKED WITH THE ACT IT IS REFUSING, IS RECORDED AS THE ONE HOLDING IT, AND THE WALK
    STOPS THERE — pinned as ONE atom, because that is how the property behaves. This assertion used
    to be `toContain("if (stack[index]()) return true;")`; when the interceptor grew its argument it
    was split into three `toContain`s, one of which was the bare `return true;` — a substring the
    interceptor body carries whatever shape the loop has, so a rewrite that asked EVERY interceptor
    instead of stopping at the first would still have satisfied it. The block is matched whole again.
  */
  expect(guard, "the walk records the blocker and stops at it").toMatch(
    /if \(entry\(pending\)\) \{\s*held\.current = \{ act: pending, blockedBy: entry \};\s*return true;\s*\}/
  );
  expect(guard).not.toContain("const top = interceptors.current[interceptors.current.length - 1];");
  // Removal BY IDENTITY, not by popping: a teardown order React is free to choose must not be able
  // to disarm the wrong form.
  expect(guard).toContain("interceptors.current.filter((entry) => entry !== next)");
  // The cleanup runs the unregister it was handed and nothing wider. It is a wrapper now only
  // because the mount also forgets its own entry, which is what scopes `completeLeave` to it.
  expect(guard).toContain("unregister();");
  expect(guard).not.toContain("interceptors.current = [];");
  // The comment that made the old shape look correct is gone with it.
  expect(guard).not.toContain("only one form is ever on screen at a time so a single slot is enough");
});

/* ────────────────────────────────────────────────────────────────────────────
 * Craft: no inline create, and a way forward said out loud
 * ──────────────────────────────────────────────────────────────────────────── */

test("Craft is still not inline-creatable, and now the reason is written down", () => {
  const dialog = read(DIALOG);
  expect(dialog).toContain('export const INLINE_CREATABLE = ["Artisan", "ProductDocumentation", "ToolDocumentation", "Process"] as const;');
  // The docstring defended only the `Dw…` exclusions and said nothing whatever about Craft, which is
  // a genuine repository model with its own `ReferenceModel` and a stage-1 picker. An undocumented
  // omission on the first control a designer touches reads as an oversight, and the next reader
  // "fixes" it.
  expect(dialog).toContain("CRAFT IS ABSENT, AND THAT IS A DECISION RATHER THAN AN OVERSIGHT");
  expect(dialog).toContain("SHARED TAXONOMY");
  // And the condition under which the decision would be reversed, so it is a position rather than a
  // refusal.
  expect(dialog).toContain("IF THIS IS EVER REVERSED");
});

test("the craft picker offers the crafts page in a new tab, and only to somebody who can act on it", () => {
  const source = read(PICKER);
  const branch = source.slice(source.indexOf('{field.refModel === "Craft" && !disabled ? ('));
  const head = branch.slice(0, 2400);
  expect(head).toContain('href="/crafts"');
  // A NEW TAB, which is the whole point of this lane rather than a stylistic choice: the stage stays
  // open behind it, exactly as the dialog keeps it open for the other four models.
  expect(head).toContain('target="_blank"');
  expect(head).toContain('rel="noopener"');
  expect(head).toContain("{CRAFT_REGISTER_LINK}");
  expect(source).toContain('const CRAFT_REGISTER_LINK = "Add or correct a craft on the crafts page (opens in a new tab)";');

  /*
   * AND THE RANK GATE, which is the half that was missing and which turned this control into a dead
   * end for most of the people it was written for.
   *
   * `/crafts` renders its form only when `canManageCrafts(user)` — Professor and above — and below
   * that rank the page says "Ask the master admin for craft creation access". The anchor was gated on
   * `field.refModel === "Craft" && !disabled` and on NOTHING else, so a designer at stage 1 read
   * "Add or correct a craft on the crafts page", left the picker, opened a new tab and landed on a
   * read-only vocabulary list. The one control in the product added specifically so the remedy would
   * not have to be remembered was sending them somewhere they cannot act.
   *
   * Asserted through the SAME predicate the page uses rather than a rank comparison of its own: two
   * opinions about who may edit the taxonomy is how the two screens come to disagree.
   */
  expect(head).toContain("craftManager ? (");
  expect(source).toContain('import { canManageCrafts } from "@/lib/permissions";');
  expect(source).toContain("const craftManager = canManageCrafts(user);");

  // AND BELOW THAT RANK, A SENTENCE THAT IS BOTH TRUE AND ACTIONABLE — not an absence. The way
  // forward for a craft that is not in the register is the typed `craftName` box, and `craftRef` is
  // optional, so saying so keeps stage 1 unblocked instead of sending anybody looking for signal or
  // for a page they cannot use.
  expect(head).toContain("{CRAFT_REGISTER_BLOCKED}");
  expect(source).toContain("Adding a craft to the register needs craft-creation access");
  expect(source).toContain("the craft's name in the Craft box above and the stage still saves");
  // Android's picker offers NEITHER sentence today — its empty craft list is a claim about this
  // device's cache. When it gains its half it must carry these words, not a second phrasing.
  expect(source).toContain("DwReferenceSelectField");
});

test("the roster picker says why it does not edit, rather than leaving the asymmetry unexplained", () => {
  // The single picker has a pencil gated on `selectedId`, which a multi-select has no analogue of.
  // The edit path is one row-expansion away — `bulkField` resolves to `participant.artisanRef` alone
  // and every roster ROW renders its own `StageReferenceSelect` — and that is the better path, so
  // the docstring argues it rather than a per-option pencil being bolted on.
  const source = read(PICKER);
  expect(source).toContain("WHY IT DOES NOT ALSO EDIT");
  expect(source).toContain("BUILD-A-SELECTION control");
});
